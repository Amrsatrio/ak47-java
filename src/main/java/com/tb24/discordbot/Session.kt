package com.tb24.discordbot

import com.mojang.brigadier.Command
import com.tb24.discordbot.commands.BrigadierCommand
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.managers.ChannelsManager
import com.tb24.discordbot.managers.HomebaseManager
import com.tb24.discordbot.managers.PartyManager
import com.tb24.discordbot.util.*
import com.tb24.discordbot.webcampaign.WebCampaign
import com.tb24.fn.EpicApi
import com.tb24.fn.event.ProfileUpdatedEvent
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.AccountMutationResponse
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpLootEntry
import com.tb24.fn.model.mcpprofile.item.FortGiftBoxItem
import com.tb24.fn.network.AccountService.GrantType.exchangeCode
import com.tb24.fn.util.EAuthClient
import com.tb24.fn.util.getPreviewImagePath
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.ModalInteraction
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class Session @JvmOverloads constructor(val client: DiscordBot, val id: String, private var persistent: Boolean = true) {
	companion object {
		val LOGGER: Logger = LoggerFactory.getLogger("Session")
	}
	val api: EpicApi
	val otherClientApis = ConcurrentHashMap<EAuthClient, EpicApi>()
	var channelsManager: ChannelsManager
	val homebaseManagers = hashMapOf<String, HomebaseManager>()
	val partyManagers = hashMapOf<String, PartyManager>()
	val webCampaignManagers = hashMapOf<String, WebCampaign>()
	val avatarCache = hashMapOf<String /*accountId*/, Pair<String /*icon*/, Int /*background*/>>()

	init {
		var client = client.okHttpClient
		val proxyHost = pickProxyHost()
		if (proxyHost != null) {
			client = client.newBuilder()
				.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, 3128)))
				.proxyAuthenticator(ProxyManager.PROXY_AUTHENTICATOR)
				.build()
		}
		this.api = EpicApi(client)
		this.channelsManager = ChannelsManager(api)
		if (persistent) SessionPersister.get(id)?.apply {
			api.setToken(token)
			api.currentLoggedIn = accountData
		}
		api.eventBus.register(this)
		LOGGER.info("$id: Created")
	}

	@Synchronized
	@Throws(HttpException::class, IOException::class)
	fun login(source: CommandSourceStack?, fields: Map<String, String>, auth: EAuthClient = EAuthClient.FORTNITE_ANDROID_GAME_CLIENT, sendMessages: Boolean = true, usedAccountNumber: Boolean = false): Int {
		if (source != null) {
			val grantType = fields["grant_type"]
			if (grantType != "device_auth" && grantType != "device_code" && source.message != null && source.guild?.selfMember?.hasPermission(Permission.MESSAGE_MANAGE) == true && sendMessages) {
				source.message!!.delete().queue()
			}
			if (api.userToken != null) {
				logout()
			}
			if (grantType != "device_code") {
				if (sendMessages) {
					source.errorTitle = "Login Failed"
					source.loading("Signing in to Epic services")
				}
				LOGGER.info("Requesting login for user {} with grant type {}", source.author.asTag, grantType)
			}
		}
		val token = api.accountService.getAccessToken(auth.asBasicAuthString(), fields, "eg1", null).exec().body()!!
		api.setToken(token)
		val accountData = api.accountService.findAccountsByIds(Collections.singletonList(token.account_id)).exec().body()?.firstOrNull()
		api.currentLoggedIn = accountData
		save()
		if (source != null && sendMessages && accountData != null) {
			val embed = createLoginMessageEmbed(accountData)
			val inviteLink = BotConfig.get().homeGuildInviteLink
			if (inviteLink != null) {
				val homeGuild = client.discord.getGuildById(BotConfig.get().homeGuildId)
				if (homeGuild != null && runCatching { homeGuild.retrieveMemberById(source.author.idLong).complete() }.isFailure) {
					embed.setDescription("Have questions, issues, or suggestions? Want to stay updated with the bot's development or vibe with us? [Join our support server!](%s)".format(inviteLink))
				}
			}
			val dbDevices = client.savedLoginsManager.getAll(id)
			val accountIndex = dbDevices.indexOfFirst { it.accountId == accountData.id }
			if (BotConfig.get().allowUsersToCreateDeviceAuth && accountIndex == -1 && dbDevices.size < source.getSavedAccountsLimit()) {
				embed.setFooter("Tip: do %ssavelogin to stay logged in".format(source.prefix))
			}
			if (accountIndex != -1 && !usedAccountNumber) {
				embed.setFooter("Tip: use %si %d to quickly switch to this account".format(source.prefix, accountIndex + 1))
			}
			source.complete(if (source.interaction is ModalInteraction) source.author.asMention else null, embed.build())
		}
		return Command.SINGLE_SUCCESS
	}

	@Synchronized
	fun logout(source: CommandSourceStack? = null) {
		source?.loading("Logging out")
		var logoutSuccess = false
		try {
			api.userToken?.let {
				api.accountService.killSession(it.access_token).exec()
				logoutSuccess = true
			}
		} catch (ignored: HttpException) {
		}
		if (logoutSuccess) {
			source?.complete("✅ Logged out successfully.")
		} else {
			source?.complete("✅ Already logged out.")
		}
		clear()
	}

	fun save() {
		if (persistent) SessionPersister.set(this)
	}

	fun clear() {
		api.clear()
		otherClientApis.clear()
		if (persistent) SessionPersister.remove(id)
	}

	fun getApiForOtherClient(authClient: EAuthClient) = otherClientApis.getOrPut(authClient) {
		val exchangeCode = api.accountService.getExchangeCode().exec().body()!!.code
		val newApi = EpicApi(client.okHttpClient)
		val token = newApi.accountService.getAccessToken(authClient.asBasicAuthString(), exchangeCode(exchangeCode), "eg1", null).exec().body()!!
		newApi.userToken = token
		newApi.currentLoggedIn = api.currentLoggedIn
		newApi
	}

	fun createLoginMessageEmbed(user: GameProfile): EmbedBuilder {
		val (avatar, avatarBackground) = getAvatar(user.id)
		val embed = EmbedBuilder().setColor(if (avatarBackground != -1) avatarBackground else BrigadierCommand.COLOR_INFO)
			.setTitle("👋 Welcome, %s".format(user.displayName?.escapeMarkdown() ?: "Unknown"))
			.addField("Account ID", user.id, false)
		if (avatar.isNotEmpty()) {
			embed.setThumbnail(avatar)
		}
		embed.setDescription(user.renderPublicExternalAuths().joinToString(" "))
		return embed
	}

	fun handleAccountMutation(response: AccountMutationResponse) {
		val newAccountInfo = response.accountInfo.run { GameProfile(id, epicDisplayName) }
		api.currentLoggedIn = newAccountInfo
		otherClientApis.values.forEach { it.currentLoggedIn = newAccountInfo }
		if (response.oauthSession != null) {
			api.setToken(response.oauthSession)
		}
		save()
	}

	@Throws(HttpException::class)
	fun queryUsers(ids: Iterable<String>) = ids
		.chunked(100)
		.map { api.accountService.findAccountsByIds(it).future() }
		.apply { CompletableFuture.allOf(*toTypedArray()).await() }
		.flatMap { it.get().body()!!.toList() }

	@Throws(HttpException::class)
	fun queryUsersMap(ids: Iterable<String>): Map<String, GameProfile> {
		val futures = ids.chunked(100).map { api.accountService.findAccountsByIds(it).future() }
		CompletableFuture.allOf(*futures.toTypedArray()).await()
		val results = hashMapOf<String, GameProfile>()
		for (future in futures) {
			future.get().body()!!.associateByTo(results) { it.id }
		}
		return results
	}

	fun getHomebase(accountId: String) = homebaseManagers.getOrPut(accountId) {
		val hb = HomebaseManager(accountId, api)
		val campaign = api.profileManager.getProfileData(accountId, "campaign")
		if (campaign != null) {
			hb.updated(ProfileUpdatedEvent("campaign", campaign, null))
		}
		hb
	}

	fun getPartyManager(accountId: String) = partyManagers.getOrPut(accountId) { PartyManager(api) }

	fun getWebCampaignManager(id: String): WebCampaign {
		return webCampaignManagers.getOrPut(id) {
			val newManager = WebCampaign(api.okHttpClient)
			newManager.connect()
			newManager
		}
	}

	fun getAvatar(accountId: String) = avatarCache.getOrPut(accountId) {
		val response = api.avatarService.queryAvatars("fortnite", accountId).execute()
		if (!response.isSuccessful) {
			return@getOrPut "" to -1
		}
		val avatarId = response.body()!!.first().avatarId
		if (avatarId.isEmpty()) {
			return@getOrPut "" to -1
		}
		val item = FortItemStack(avatarId, 1)
		val icon = item.getPreviewImagePath()?.toString()?.let { Utils.benBotExportAsset(it) } ?: ""
		val background = item.palette.Color2.toFColor(true).toPackedARGB() and 0xFFFFFF
		icon to background
	}

	private fun pickProxyHost(): String? {
		val random = Random(id.toLongOrNull() ?: Long.MAX_VALUE)
		val pickedHost = client.proxyManager.pickOne(random)
		if (pickedHost != null) LOGGER.info("$id: Using proxy host $pickedHost")
		return pickedHost
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	fun onProfileUpdated(event: ProfileUpdatedEvent) {
		if (true) return
		val profile = event.profileObj
		try {
			if (profile.profileId == "common_core") {
				if (event.profileChanges.none { it["changeType"].asString == "fullProfileUpdate" }) {
					return
				}
				for (item in profile.items.values) {
					if (item.primaryAssetType == "GiftBox") {
						val attrs = item.getAttributes(FortGiftBoxItem::class.java)
						val fromAccount = attrs.fromAccountId?.run { queryUsers(Collections.singleton(attrs.fromAccountId)).firstOrNull() }
						var icon: String? = null
						var title: String
						var line1 = ""
						var line2 = ""
						when (item.primaryAssetName) {
							"gb_ungiftbox" -> {
								icon = Utils.benBotExportAsset("/Game/UI/Login/Art/T-EpicGames-Logo.T-EpicGames-Logo")
								title = "EPIC GAMES"
								line1 = "The following items have been **removed** from your account:"
								line2 = if (attrs.fromAccountId != null) {
									val dn = fromAccount?.displayName ?: attrs.fromAccountId
									"Reason: A gift from **$dn** was reversed. For additional details, you may wish to contact them directly."
								} else {
									"Reason: A payment made by this account was reversed or refunded."
								}
							}
							// FortGiftBoxItemDefinition.GiftWrapType == EFortGiftWrapType::UserFree
							"gb_default",
							"gb_giftbothmodes",
							"gb_giftwrap1", "gb_giftwrap2", "gb_giftwrap3", "gb_giftwrap4",
							"gb_s10_gifter", "gb_s11_gifter", "gb_s12_gifter", "gb_s13_gifter", "gb_s14_gifter",
							"gb_stwgift" -> {
								title = "You received a gift!"
								attrs.fromAccountId?.apply {
									//icon = "https://cdn2.unrealengine.com/Kairos/portraits/${channelsManager.getUserSettings(this, "avatar")[0]}.png?preview=1"
									line1 = "From: ${fromAccount?.displayName ?: attrs.fromAccountId}"
								}
								attrs.params["userMessage"]?.apply {
									line2 = asString
								}
							}
							else -> continue
						}
						val customLootList = mutableListOf<McpLootEntry>()
						var mtxCount = 0
						for (loot in attrs.lootList) {
							if (loot.itemType.startsWith("Currency:Mtx")) {
								mtxCount += loot.quantity
							} else {
								customLootList += loot
							}
						}
						if (mtxCount > 0) {
							customLootList += McpLootEntry().apply {
								itemType = "Currency:MtxPurchased"
								quantity = mtxCount
							}
						}
						val embed = EmbedBuilder()
							.setAuthor(title, null, icon)
							.setDescription(line1 + '\n' + line2)
							.setTimestamp(attrs.giftedOn.toInstant())
							.apply {
								addFieldSeparate("Items", customLootList) { it.asItemStack().render() }
							}
							.addField("Your Account ID", api.currentLoggedIn.id, false)
							.addField("Gift ID", item.itemId, false)
							.setFooter("React this with anything to acknowledge")
							.build()
						val user = client.discord.retrieveUserById(id).complete()
						val channel = user.openPrivateChannel().complete()
						channel.sendMessageEmbeds(embed).complete()
					}
				}
			}
		} catch (e: Throwable) {
			LOGGER.error("Handle profile update failure", e)
		}
	}
}