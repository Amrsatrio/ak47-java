package com.tb24.discordbot.commands

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.*
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpProfile
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.interactions.commands.CommandInteraction
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.internal.interactions.InteractionHookImpl
import net.dv8tion.jda.internal.requests.restaction.MessageActionImpl
import net.dv8tion.jda.internal.requests.restaction.interactions.ReplyCallbackActionImpl
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture

open class CommandSourceStack : CollectorFilter<Any> {
	companion object {
		val IS_DEBUG = System.getProperty("intellij.debug.agent") == "true"
	}

	val client: DiscordBot
	var message: Message? = null
	var interaction: IReplyCallback? = null
		private set
	var hook: InteractionHook? = null
	private var interactionCompleted = false

	val jda: JDA
	val guild: Guild?
	val member: Member?
	val _user: User?
	inline val author get() = _user!! // name should be user but whatever
	val channel: MessageChannel

	val initialSession: Session
	var session: Session
	inline val api get() = session.api

	val prefix: String
	var interactionCommand: CommandBuilder<CommandSourceStack, *, *>? = null
	var commandName: String? = null
	var unattended = false

	constructor(client: DiscordBot, message: Message, sessionId: String?, ignoreSessionLimit: Boolean = false) {
		this.client = client
		this.message = message
		jda = message.jda
		guild = if (message.isFromGuild) message.guild else null
		member = message.member
		_user = message.author
		channel = message.channel
		initialSession = getInitialSession(sessionId, ignoreSessionLimit)
		session = initialSession
		prefix = client.getCommandPrefix(guild)
	}

	constructor(client: DiscordBot, interaction: Interaction, sessionId: String?, ignoreSessionLimit: Boolean = false) {
		this.client = client
		this.interaction = interaction as? IReplyCallback
		jda = interaction.jda
		guild = interaction.guild
		member = interaction.member
		_user = interaction.user
		channel = interaction.channel as MessageChannel
		initialSession = getInitialSession(sessionId, ignoreSessionLimit)
		session = initialSession
		prefix = "/"
	}

	constructor(client: DiscordBot, channel: MessageChannel) {
		this.client = client
		jda = channel.jda
		guild = (channel as? GuildChannel)?.guild
		member = null
		_user = (channel as? PrivateChannel)?.retrieveUser()?.complete()
		this.channel = channel
		initialSession = client.internalSession
		session = initialSession
		prefix = client.getCommandPrefix(guild)
	}

	private fun getInitialSession(sessionId: String?, ignoreSessionLimit: Boolean) =
		sessionId?.let { client.getSession(sessionId) { ignoreSessionLimit || hasPremium() } } ?: client.internalSession

	// region Flow control
	var errorTitle: String? = null
		get() = field ?: Utils.randomError()

	var loadingMsg: Message? = null

	fun loading(text: String?): Message? {
		val interaction = interaction
		if (interaction != null && !interactionCompleted) {
			if (hook == null) {
				hook = interaction.deferReply().complete()
			}
			return null // FYI we cannot show the custom loading message, it's always "<Name> is thinking..."
		}
		var loadingText = Utils.loadingText(text ?: "Loading")
		if (IS_DEBUG) {
			loadingText = "[DEBUGGER ATTACHED] $loadingText"
		}
		return (loadingMsg?.editMessage(loadingText) ?: channel.sendMessage(loadingText)).override(true).complete().also { loadingMsg = it }
	}

	fun complete(text: String?, embed: MessageEmbed? = null, vararg actionRows: ActionRow): Message {
		val builder = MessageBuilder(text)
		if (embed != null) {
			builder.setEmbeds(embed)
		}
		if (actionRows.isNotEmpty()) {
			builder.setActionRows(*actionRows)
		}
		return complete(builder.build())
	}

	inline fun complete(vararg files: AttachmentUpload) = complete(null, *files)

	fun complete(message: Message?, vararg files: AttachmentUpload): Message {
		val interaction = interaction
		if (interaction != null && !interactionCompleted) {
			interactionCompleted = true
			val localHook = hook
			return if (localHook != null) {
				hook = null
				val action = (localHook as InteractionHookImpl).editRequest("@original")
				message?.let { action.applyMessage(it) }
				if (files.isNotEmpty()) {
					action.retainFiles(emptySet())
					files.forEach { action.addFile(it.data, it.name, *it.options) }
				}
				action.complete()
			} else {
				val action = interaction.deferReply()
				message?.let { (action as ReplyCallbackActionImpl).applyMessage(it) }
				if (files.isNotEmpty()) {
					//action.retainFiles(emptySet())
					files.forEach { action.addFile(it.data, it.name, *it.options) }
				}
				action.complete().retrieveOriginal().complete()
			}
		}
		val action = loadingMsg?.let { MessageActionImpl(jda, it.id, channel) } ?: MessageActionImpl(jda, null, channel)
		action.apply(message)
		if (files.isNotEmpty()) {
			action.retainFiles(emptySet())
			files.forEach { action.addFile(it.data, it.name, *it.options) }
		}
		val sentMessage = action.override(true).complete()
		loadingMsg = null
		return sentMessage
	}
	// endregion

	inline fun getOption(name: String) = (interaction as? CommandInteraction)?.getOption(name)

	inline fun <reified T> getArgument(name: String): T? {
		val argumentType = interactionCommand!!.optionArguments[name]!! as ArgumentType<T>
		val option = getOption(name) ?: return null
		return argumentType.parse(StringReader(option.asString))
	}

	@Throws(CommandSyntaxException::class)
	fun ensureSession() {
		if (api.userToken == null) {
			throw SimpleCommandExceptionType(LiteralMessage("You're not logged in to an Epic account. Do `${prefix}login`, preferably in DMs, to log in.")).create()
		}
	}

	fun conditionalUseInternalSession(): Boolean {
		if (api.userToken == null) {
			session = client.internalSession
			return true
		}
		return false
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
		warnCodeToken()
		return "https://www.epicgames.com/id/exchange?exchangeCode=${api.accountService.exchangeCode.exec().body()!!.code}&redirectUrl=${URLEncoder.encode(url, "UTF-8")}"
	}

	fun warnCodeToken() {
		if (guild != null && BotConfig.get().homeGuildInviteLink != null && !complete(null, createEmbed().setColor(BrigadierCommand.COLOR_WARNING)
				.setTitle("✋ Hold up!")
				.setDescription("We're about to send something that carries your current Epic account session which will be valid for some time or until you log out. Make sure you trust the people here, or you may do the command again [in DMs](${getPrivateChannelLink()}).\n\nContinue?")
				.build(), confirmationButtons()).awaitConfirmation(this).await()) {
			throw SimpleCommandExceptionType(LiteralMessage("Alright.")).create()
		}
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

	// region CollectorFilter<Any> interface
	override fun invoke(item: Any, user: User?, collected: Map<Any, Any>): Boolean {
		if (user == author) {
			return true
		}
		if (item is IReplyCallback) {
			item.reply("That interface is not yours.").setEphemeral(true).queue()
		}
		return false
	}
	// endregion
}