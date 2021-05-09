package com.tb24.discordbot.commands

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.Rune
import com.tb24.discordbot.Session
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.exec
import com.tb24.fn.EpicApi
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpProfile
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.internal.entities.ReceivedMessage
import java.awt.Color
import java.net.URLEncoder

open class CommandSourceStack(val client: DiscordBot, val message: Message, sessionId: String?) {
	// Message delegates
	inline val author get() = message.author
	inline val channel get() = message.channel
	inline val guild get() = message.guild
	inline val member get() = message.member
	inline fun isFromType(type: ChannelType) = message.isFromType(type)

	val initialSession: Session = sessionId?.let { client.getSession(sessionId) } ?: client.internalSession
	var session = initialSession
	inline val api get() = session.api

	val prefix: String by lazy { client.getCommandPrefix(message) }

	// region Flow control
	var errorTitle: String? = null
		get() = field ?: Utils.randomError()

	var loadingMsg: Message? = null

	fun loading(text: String?): Message {
		val loadingText = Utils.loadingText(text ?: "Loading")
		return (loadingMsg?.editMessage(loadingText) ?: channel.sendMessage(loadingText)).override(true).complete().also { loadingMsg = it }
	}

	fun complete(text: String?, embed: MessageEmbed? = null): Message {
		val message = MessageBuilder().setContent(text).setEmbed(embed).build()
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
		val authorName = if (hasCampaign) "[%,d] %s".format(session.getHomebase(user.id).calcEnergyByFORT(phoenixRating).toInt(), user.displayName) else user.displayName
		val (avatar, avatarBackground) = session.channelsManager.getUserSettings(user.id, "avatar", "avatarBackground")
		return EmbedBuilder()
			.setAuthor(authorName, null, avatar?.let { "https://cdn2.unrealengine.com/Kairos/portraits/$it.png?preview=1" })
			.setColor(Color.decode(EpicApi.GSON.fromJson(avatarBackground, Array<String>::class.java)[1]))
	}

	@Throws(HttpException::class)
	fun queryUsers(ids: Iterable<String>) = session.queryUsers(ids)

	@Throws(HttpException::class)
	fun generateUrl(url: String): String {
		if (!isFromType(ChannelType.PRIVATE)) {
			throw SimpleCommandExceptionType(LiteralMessage("Please invoke the command again in DMs, as we have to send you info that carries over your current session.")).create()
		}
		return "https://www.epicgames.com/id/exchange?exchangeCode=${api.accountService.exchangeCode.exec().body()!!.code}&redirectUrl=${URLEncoder.encode(url, "UTF-8")}"
	}

	@Throws(CommandSyntaxException::class)
	fun ensureCompletedCampaignTutorial(campaign: McpProfile) {
		val completedTutorial = campaign.items.values.firstOrNull { it.templateId == "Quest:homebaseonboarding" }?.attributes?.get("completion_hbonboarding_completezone")?.asInt ?: 0 > 0
		if (!completedTutorial) {
			throw SimpleCommandExceptionType(LiteralMessage("To continue, the account must own Save the World and completed the tutorial.")).create()
		}
	}

	fun hasPremium(): Boolean {
		return r.table("members").get(author.id).run(client.dbConn).first() != null
	}

	fun getSavedAccountsLimit() = when {
		Rune.isBotDev(this) || author.idLong == 720148351626248192L -> 20
		hasPremium() -> 10
		else -> {
			val timeCreated = author.timeCreated.toEpochSecond()
			val accountAge = System.currentTimeMillis() / 1000 - timeCreated
			if (accountAge < 180 * 24 * 60 * 60) 0 else 2
		}
	}
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
	null,
	null,
	null,
	null,
	emptyList(),
	emptyList(),
	emptyList(),
	emptyList(),
	0
), null)

class PrivateChannelCommandSource(client: DiscordBot, channel: PrivateChannel) : CommandSourceStack(client, ReceivedMessage(
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
	channel.user,
	null,
	null,
	null,
	emptyList(),
	emptyList(),
	emptyList(),
	emptyList(),
	0
), null)