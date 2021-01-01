package com.tb24.discordbot.util

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageReaction
import net.dv8tion.jda.api.entities.User
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

fun <T> Message.replyPaginated(all: List<T>,
							   pageSize: Int = 9,
							   messageToEdit: Message? = null,
							   initialPage: Int = 0,
							   customReactions: PaginatorCustomReactions<T>? = null,
							   render: (content: List<T>, page: Int, pageCount: Int) -> Message) {
	val pageCount = ceil(all.size / pageSize.toFloat()).toInt()
	var page = initialPage
	val rendered = render(all.subList(page * pageSize, min(all.size, (page * pageSize) + pageSize)), page, pageCount)
	val msg = (messageToEdit?.editMessage(rendered)?.override(true) ?: channel.sendMessage(rendered)).complete()
	if (pageCount <= 1) {
		return
	}
	val reactions = mutableListOf("⏮", "◀", "▶", "⏭")
	customReactions?.addReactions(reactions)
	reactions.forEach { msg.addReaction(it).queue() }
	val collector = msg.createReactionCollector({ reaction, user, _ -> reactions.contains(reaction.reactionEmote.name) && user?.idLong == author.idLong }, ReactionCollectorOptions().apply { idle = 30000L })
	collector.callback = object : CollectorListener<MessageReaction> {
		override fun onCollect(item: MessageReaction, user: User?) {
			val oldPage = page
			page = when (reactions.indexOf(item.reactionEmote.name)) {
				0 -> 0
				1 -> max(page - 1, 0)
				2 -> min(page + 1, pageCount - 1)
				3 -> pageCount - 1
				else -> {
					customReactions?.handleReaction(collector, item, user, page, pageCount)
					return
				}
			}

			if (msg.member != null && msg.member!!.hasPermission(Permission.MESSAGE_MANAGE)) {
				msg.removeReaction(item.reactionEmote.name, user!!).queue()
			}

			if (page != oldPage) {
				msg.editMessage(render(all.subList(page * pageSize, min(all.size, (page * pageSize) + pageSize)), page, pageCount)).queue()
			}
		}

		override fun onRemove(item: MessageReaction, user: User?) {}

		override fun onDispose(item: MessageReaction, user: User?) {}

		override fun onEnd(collected: Map<Any, MessageReaction>, reason: CollectorEndReason) {
			if (reason == CollectorEndReason.IDLE && msg.member != null && msg.member!!.hasPermission(Permission.MESSAGE_MANAGE)) {
				msg.clearReactions().queue()
			}
		}
	}
}

interface PaginatorCustomReactions<T> {
	fun addReactions(reactions: MutableCollection<String>)
	fun handleReaction(collector: ReactionCollector, item: MessageReaction, user: User?, page: Int, pageCount: Int)
}