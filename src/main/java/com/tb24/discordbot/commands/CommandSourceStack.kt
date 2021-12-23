package com.tb24.discordbot.commands

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.*
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpProfile
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.commands.CommandInteraction
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.internal.entities.ReceivedMessage
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture

open class CommandSourceStack {
	companion object {
		val IS_DEBUG = System.getProperty("intellij.debug.agent") == "true"
	}

	val client: DiscordBot
	lateinit var message: Message
	private var _interaction: Interaction? = null
	val interaction get() = _interaction!!
	val commandInteraction get() = _interaction as CommandInteraction

	val guild: Guild?
	val member: Member?
	val author: User // name should be user but whatever
	val channel: MessageChannel

	val initialSession: Session
	var session: Session
	inline val api get() = session.api

	constructor(client: DiscordBot, message: Message, sessionId: String?, ignoreSessionLimit: Boolean = false) {
		this.client = client
		this.message = message
		guild = message.guild
		member = message.member
		author = message.author
		channel = message.channel
		initialSession = getInitialSession(sessionId, ignoreSessionLimit)
		session = initialSession
	}

	constructor(client: DiscordBot, interaction: Interaction, sessionId: String?, ignoreSessionLimit: Boolean = false) {
		this.client = client
		this._interaction = interaction
		guild = interaction.guild
		member = interaction.member
		author = interaction.user
		channel = interaction.channel as MessageChannel // TODO IMPORTANT
		initialSession = getInitialSession(sessionId, ignoreSessionLimit)
		session = initialSession
	}

	private fun getInitialSession(sessionId: String?, ignoreSessionLimit: Boolean) =
		sessionId?.let { client.getSession(sessionId, ignoreSessionLimit || hasPremium()) } ?: client.internalSession

	inline fun isFromType(type: ChannelType) = message.isFromType(type)

	val prefix: String by lazy { DiscordBot.instance.getCommandPrefix(message) }

	// region Flow control
	var errorTitle: String? = null
		get() = field ?: Utils.randomError()

	var loadingMsg: Message? = null

	fun loading(text: String?): Message {
		var loadingText = Utils.loadingText(text ?: "Loading")
		if (IS_DEBUG) {
			loadingText = "[DEBUGGER ATTACHED] $loadingText"
		}
		return (loadingMsg?.editMessage(loadingText) ?: channel.sendMessage(loadingText)).override(true).complete().also { loadingMsg = it }
	}

	fun complete(text: String?, embed: MessageEmbed? = null, vararg actionRows: ActionRow): Message {
		val builder = MessageBuilder().setContent(text)
		if (embed != null) {
			builder.setEmbeds(embed)
		}
		if (actionRows.isNotEmpty()) {
			builder.setActionRows(*actionRows)
		}
		val message = builder.build()
		val complete = (loadingMsg?.editMessage(message) ?: channel.sendMessage(message)).override(true).complete()
		loadingMsg = null
		return complete
	}
	// endregion

	@Throws(CommandSyntaxException::class)
	fun ensureSession() {
		if (api.userToken == null) {
			throw SimpleCommandExceptionType(LiteralMessage("You're not logged in to an Epic account. Do `${prefix}login`, preferably in DMs, to log in.")).create()
		}
	}

	@Throws(HttpException::class)
	fun createEmbed(user: GameProfile = api.currentLoggedIn, phoenixRating: Boolean = false): EmbedBuilder {
		val hasCampaign = session.api.profileManager.hasProfileData(user.id, "campaign")
		val authorName = if (hasCampaign) "[%,.1f] %s".format(session.getHomebase(user.id).calcEnergyByFORT(phoenixRating), user.displayName) else user.displayName
		val (avatar, avatarBackground) = session.getAvatar(user.id)
		return EmbedBuilder().setColor(if (avatarBackground != -1) avatarBackground else BrigadierCommand.COLOR_INFO).setAuthor(authorName, null, avatar.ifEmpty { null })
	}

	val userCache = hashMapOf<String, GameProfile>()

	@Throws(HttpException::class)
	fun queryUsers(ids: Iterable<String>) = session.queryUsers(ids)

	@Throws(HttpException::class)
	fun queryUsers_map(ids: Collection<String>) {
		if (ids.isEmpty()) {
			return
		}
		ids.filter { !userCache.containsKey(it) }
			.chunked(100)
			.map { session.api.accountService.findAccountsByIds(it).future() }
			.apply { CompletableFuture.allOf(*toTypedArray()).await() }
			.flatMap { it.get().body()!!.toList<GameProfile>() }
			.associateByTo(userCache) { it.id }
	}

	@Throws(HttpException::class)
	fun generateUrl(url: String): String {
		if (!isFromType(ChannelType.PRIVATE) && !complete(null, createEmbed().setColor(BrigadierCommand.COLOR_WARNING)
				.setTitle("✋ Hold up!")
				.setDescription("We're about to send a link that carries your current session which will be valid for some time or until you log out. Make sure you trust the people here, or you may do the command again [in DMs](${getPrivateChannelLink()}).\n\nContinue?")
				.build(), confirmationButtons()).awaitConfirmation(author).await()) {
			throw SimpleCommandExceptionType(LiteralMessage("Alright.")).create()
		}
		return "https://www.epicgames.com/id/exchange?exchangeCode=${api.accountService.exchangeCode.exec().body()!!.code}&redirectUrl=${URLEncoder.encode(url, "UTF-8")}"
	}

	@Throws(CommandSyntaxException::class)
	fun ensureCompletedCampaignTutorial(campaign: McpProfile) {
		val completedTutorial = (campaign.items.values.firstOrNull { it.templateId == "Quest:homebaseonboarding" }?.attributes?.get("completion_hbonboarding_completezone")?.asInt ?: 0) > 0
		if (!completedTutorial) {
			throw SimpleCommandExceptionType(LiteralMessage("To continue, the account must own Save the World and completed the tutorial.")).create()
		}
	}

	fun hasPremium(): Boolean {
		return r.table("members").get(author.id).run(client.dbConn).first() != null
	}

	fun ensurePremium(description: String? = null) {
		if (hasPremium()) return
		val homeGuild = client.discord.getGuildById(BotConfig.get().homeGuildId)
			?: throw SimpleCommandExceptionType(LiteralMessage("Premium required to use this feature.")).create()
		val role = homeGuild.getRolesByName("premium", true).firstOrNull()
			?: throw SimpleCommandExceptionType(LiteralMessage("No role in ${homeGuild.name} named Premium.")).create()
		val embed = EmbedBuilder().setColor(role.color)
			.setTitle("🌟 You've discovered a premium feature!")
			.setDescription("${description?.let { "**$it with premium!**\n" } ?: ""}To get premium, you can ")
		if (runCatching { homeGuild.retrieveMemberById(author.idLong).complete() }.isFailure) {
			embed.appendDescription("[join our support server](${BotConfig.get().homeGuildInviteLink}) and ")
		}
		embed.appendDescription("visit <#${BotConfig.get().premiumChannelId}> to see the available options.")
		throw SimpleCommandExceptionType(EmbedMessage(embed.build())).create()
	}

	fun getSavedAccountsLimit(): Int {
		val quotaSettings = BotConfig.get().deviceAuthQuota
		return when {
			Rune.isBotDev(this) || author.idLong in quotaSettings.additionalPrivilegedUserIds -> quotaSettings.maxForPrivileged
			hasPremium() -> quotaSettings.maxForPremium
			else -> {
				val timeCreated = author.timeCreated.toEpochSecond()
				val accountAge = System.currentTimeMillis() / 1000 - timeCreated
				if (accountAge >= quotaSettings.minAccountAgeInDaysForComplimentary * 24 * 60 * 60) quotaSettings.maxForComplimentary else 0
			}
		}
	}

	fun getPrivateChannelLink() = author.openPrivateChannel().complete().let { "https://discord.com/channels/%s/%s".format("@me", it.id) }
}

class OnlyChannelCommandSource(client: DiscordBot, channel: MessageChannel) : CommandSourceStack(client, ReceivedMessage(
	-1L,
	channel,
	MessageType.DEFAULT,
	null,
	false,
	false,
	null,
	null,
	false,
	false,
	null,
	null,
	(channel as? PrivateChannel)?.user,
	null,
	null,
	null,
	emptyList(),
	emptyList(),
	emptyList(),
	emptyList(),
	emptyList(),
	0,
	null,
), null, true)
