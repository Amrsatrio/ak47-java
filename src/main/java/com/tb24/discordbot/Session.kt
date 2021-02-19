package com.tb24.discordbot

import com.mojang.brigadier.Command
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.commands.GrantType
import com.tb24.discordbot.managers.ChannelsManager
import com.tb24.discordbot.managers.HomebaseManager
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.event.ProfileUpdatedEvent
import com.tb24.fn.model.account.AccountMutationResponse
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpLootEntry
import com.tb24.fn.model.mcpprofile.item.GiftBoxAttributes
import com.tb24.fn.util.EAuthClient
import com.tb24.fn.util.Utils.DEFAULT_GSON
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.internal.entities.UserImpl
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.awt.Color
import java.io.IOException
import java.util.*
import java.util.concurrent.CompletableFuture

class Session @JvmOverloads constructor(val client: DiscordBot, val id: String, private var persistent: Boolean = true) {
	companion object {
		val LOGGER = LoggerFactory.getLogger("Session")
	}

	val api = EpicApi(client.okHttpClient)
	var channelsManager = ChannelsManager(api)
	val homebaseManagers = hashMapOf<String, HomebaseManager>()

	init {
		if (persistent) SessionPersister.get(id)?.apply {
			api.userToken = token
			api.currentLoggedIn = accountData
		}
		api.buildServices()
		api.eventBus.register(this)
		LOGGER.info("Created session $id")
	}

	@Throws(HttpException::class, IOException::class)
	fun login(source: CommandSourceStack?, grantType: GrantType, fields: Map<String, String>, auth: EAuthClient = EAuthClient.FORTNITE_IOS_GAME_CLIENT, sendMessages: Boolean = true): Int {
		if (source != null) {
			if (grantType != GrantType.device_auth && grantType != GrantType.device_code && source.message.isFromGuild && sendMessages) {
				source.message.delete().queue()
			}
			if (api.userToken != null) {
				logout(if (sendMessages) source.message else null)
			}
			if (grantType != GrantType.device_code) {
				if (sendMessages) {
					source.errorTitle = "Login Failed"
					source.loading("Signing in to Epic services")
				}
				LOGGER.info("Requesting login for user {} with grant type {}", source.author.asTag, grantType)
			}
		}
		val token = api.accountService.getAccessToken(auth.asBasicAuthString(), grantType.name, fields, null).exec().body()!!
		api.userToken = token
		api.buildServices()
		val accountData = api.accountService.findAccountsByIds(Collections.singletonList(token.account_id)).exec().body()?.firstOrNull()
		api.currentLoggedIn = accountData
		save()
		if (source != null && sendMessages) {
			sendLoginMessage(source)
		}
		return Command.SINGLE_SUCCESS
	}

	fun logout(message: Message?): Boolean {
		val logoutMsg = message?.run {
			if (author.idLong == client.discord.selfUser.idLong) {
				editMessage(Utils.loadingText("Logging out")).complete()
			} else {
				channel.sendMessage(Utils.loadingText("Logging out")).complete()
			}
		}
		var bError = false
		try {
			api.accountService.killSession(api.userToken.access_token).exec()
			logoutMsg?.editMessage("✅ Logged out successfully.")?.queue()
		} catch (e: HttpException) {
			logoutMsg?.editMessage("✅ Already logged out.")?.queue()
			bError = true
		}
		clear()
		return bError
	}

	fun save() {
		if (persistent) SessionPersister.set(this)
	}

	fun clear() {
		api.clear()
		if (persistent) SessionPersister.remove(id)
	}

	fun sendLoginMessage(source: CommandSourceStack, user: GameProfile? = api.currentLoggedIn) {
		if (user == null) {
			return
		}
		val avatarKeys = channelsManager.getUserSettings(user.id, ChannelsManager.KEY_AVATAR, ChannelsManager.KEY_AVATAR_BACKGROUND)
		val embed = EmbedBuilder()
			.setTitle("👋 Welcome, %s!".format(user.displayName ?: "Unknown"))
			.addField("Account ID", user.id, false)
			.setThumbnail("https://cdn2.unrealengine.com/Kairos/portraits/${avatarKeys[0]}.png?preview=1")
			.setColor(Color.decode(DEFAULT_GSON.fromJson(avatarKeys[1], Array<String>::class.java)[1]))
		user.externalAuths?.run {
			values.forEach {
				if (it.type == "psn" || it.type == "xbl" || it.type == "nintendo") {
					embed.addField(L10N.format("account.ext.${it.type}.name"), it.externalDisplayName.orDash(), true)
				}
			}
		}
		source.complete(null, embed.build())
	}

	fun handleAccountMutation(response: AccountMutationResponse) {
		api.currentLoggedIn = response.accountInfo.run { GameProfile(id, epicDisplayName) }
		if (response.oauthSession != null) {
			api.userToken = response.oauthSession
		}
		save()
	}

	@Throws(HttpException::class)
	fun queryUsers(ids: Iterable<String>) = ids
		.chunked(100)
		.map { api.accountService.findAccountsByIds(it).future() }
		.apply { CompletableFuture.allOf(*toTypedArray()).await() }
		.flatMap { it.get().body()!!.toList() }

	fun getHomebase(accountId: String) = homebaseManagers.getOrPut(accountId) {
		val hb = HomebaseManager(accountId, api)
		val campaign = api.profileManager.getProfileData(accountId, "campaign")
		if (campaign != null) {
			hb.updated(ProfileUpdatedEvent("campaign", campaign, null))
		}
		hb
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
						val attrs = item.getAttributes(GiftBoxAttributes::class.java)
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
									icon = "https://cdn2.unrealengine.com/Kairos/portraits/${channelsManager.getUserSettings(this, "avatar")[0]}.png?preview=1"
									line1 = "From: ${fromAccount?.displayName ?: attrs.fromAccountId}"
								}
								attrs.params["userMessage"]?.apply {
									line2 = this
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
						val user = client.discord.getUserById(id) ?: client.discord.retrieveUserById(id).complete()
						val channel = (user as UserImpl).privateChannel ?: user.openPrivateChannel().complete()
						channel.sendMessage(embed).complete()
					}
				}
			}
		} catch (e: Throwable) {
			LOGGER.error("Handle profile update failure", e)
		}
	}
}