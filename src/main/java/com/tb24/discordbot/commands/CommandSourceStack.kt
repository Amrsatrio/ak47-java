package com.tb24.discordbot.commands

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.L10N
import com.tb24.discordbot.Session
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.exec
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.ChannelType
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageChannel
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.internal.entities.ReceivedMessage
import java.net.URLEncoder

open class CommandSourceStack(val client: DiscordBot, val message: Message, sessionId: String) {
	// message delegates
	val author get() = message.author
	val channel get() = message.channel
	val guild get() = message.guild
	val member get() = message.member
	fun isFromType(type: ChannelType) = message.isFromType(type)

	val initialSession: Session = client.getSession(sessionId)
	var session = initialSession
	val api get() = session.api

	val prefix: String by lazy { client.getCommandPrefix(message) }

	// flow control
	var errorTitle: String? = null
		get() = field ?: Utils.randomError()

	var loadingMsg: Message? = null

	fun loading(text: String): Message {
		val loadingText = Utils.loadingText(text)
		return (loadingMsg?.editMessage(loadingText) ?: channel.sendMessage(loadingText)).override(true).complete().also { loadingMsg = it }
	}

	fun complete(text: String?, embed: MessageEmbed? = null): Message {
		val message = MessageBuilder().setContent(text).setEmbed(embed).build()
		val complete = (loadingMsg?.editMessage(message) ?: channel.sendMessage(message)).override(true).complete()
		loadingMsg = null
		return complete
	}

	@Throws(CommandSyntaxException::class)
	fun ensureSession() {
		if (api.userToken == null) {
			throw SimpleCommandExceptionType(LiteralMessage(L10N.format("account.not_logged_in"))).create()
		}
	}

	@Throws(HttpException::class)
	fun createEmbed() = EmbedBuilder().setAuthor(api.currentLoggedIn.displayName, null, session.channelsManager.getUserSettings(api.currentLoggedIn.id, "avatar").firstOrNull()?.let { "https://cdn2.unrealengine.com/Kairos/portraits/$it.png?preview=1" })

	@Throws(HttpException::class)
	fun queryUsers(ids: Iterable<String>) = session.queryUsers(ids)

	@Throws(HttpException::class)
	fun generateUrl(url: String) =
		"https://www.epicgames.com/id/exchange?exchangeCode=${api.accountService.getExchangeCode().exec().body()!!.code}&redirectUrl=${URLEncoder.encode(url, "UTF-8")}"

	@Throws(CommandSyntaxException::class)
	fun ensureCampaignAccess() {
		if (api.profileManager.getProfileData("common_core").items.values.none { it.templateId == "Token:campaignaccess" }) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have access to Save the World.")).create()
		}
	}
}

class OnlyChannelCommandSource(client: DiscordBot, channel: MessageChannel) : CommandSourceStack(client, ReceivedMessage(
	-1L,
	channel,
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
	0
), channel.id)