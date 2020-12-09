package com.tb24.discordbot

import com.tb24.discordbot.commands.OnlyChannelCommandSource
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.RemoveGiftBox
import net.dv8tion.jda.api.events.message.priv.react.PrivateMessageReactionAddEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class ReactionHandler(val client: DiscordBot) : ListenerAdapter() {
	override fun onPrivateMessageReactionAdd(event: PrivateMessageReactionAddEvent) {
		if (event.user == event.jda.selfUser) {
			return
		}
		val source = OnlyChannelCommandSource(client, event.channel)
		try {
			val message = event.channel.retrieveMessageById(event.messageId).complete()
			val embed = message.embeds.firstOrNull() ?: return
			if (embed.footer?.text != "React this with anything to acknowledge") return
			var accountId: String? = null
			var giftBoxId: String? = null
			embed.fields.forEach {
				when (it.name) {
					"Your Account ID" -> accountId = it.value
					"Gift ID" -> giftBoxId = it.value
				}
			}
			if (accountId == null || giftBoxId == null) {
				return
			}
			val session = source.client.getSession(event.userId)
			if (accountId != session.api.currentLoggedIn.id) {
				event.channel.sendMessage("Cannot acknowledge gift `$giftBoxId` because you are currently not on the account that gift was sent to.").queue()
				return
			}
			session.api.profileManager.dispatchClientCommandRequest(RemoveGiftBox().apply { giftBoxItemIds = arrayOf(giftBoxId) }).await()
			message.editMessage("✅ Acknowledged.").queue()
		} catch (e: HttpException) {
			if (client.commandManager.httpError(source, e)) {
				onPrivateMessageReactionAdd(event) // do this again
			}
		} catch (e: Throwable) {
			e.printStackTrace()
			client.commandManager.unhandledException(source, e, "\nWhile handling a reaction")
		}
	}
}