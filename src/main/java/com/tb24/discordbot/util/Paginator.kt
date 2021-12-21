package com.tb24.discordbot.util

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.Button
import net.dv8tion.jda.api.interactions.components.ComponentInteraction
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

fun <T> Message.replyPaginated(all: List<T>,
							   pageSize: Int = 9,
							   messageToEdit: Message? = null,
							   initialPage: Int = 0,
							   customReactions: PaginatorCustomComponents<T>? = null,
							   render: (content: List<T>, page: Int, pageCount: Int) -> Message) {
	val pageCount = ceil(all.size / pageSize.toFloat()).toInt()
	var page = initialPage
	val rendered = render(all.subList(page * pageSize, min(all.size, (page * pageSize) + pageSize)), page, pageCount)
	if (pageCount <= 1) {
		(messageToEdit?.editMessage(rendered)?.override(true) ?: channel.sendMessage(rendered)).complete()
		return
	}
	val rows = mutableListOf<ActionRow>()
	val pageControlButtons = ActionRow.of(
		Button.secondary("first", "⏮"),
		Button.secondary("prev", "◀"),
		Button.secondary("next", "▶"),
		Button.secondary("last", "⏭")
	)
	rows.add(pageControlButtons)
	customReactions?.modifyComponents(rows)
	val msg = (messageToEdit?.editMessage(rendered)?.override(true) ?: channel.sendMessage(rendered)).setActionRows(rows).complete()
	val collector = msg.createMessageComponentInteractionCollector({ _, user, _ -> user?.idLong == author.idLong }, MessageComponentInteractionCollectorOptions().apply {
		idle = 90000L
		//dispose = true
	})
	collector.callback = object : CollectorListener<ComponentInteraction> {
		override fun onCollect(item: ComponentInteraction, user: User?) {
			val oldPage = page
			page = when (item.componentId) {
				"first" -> 0
				"prev" -> max(page - 1, 0)
				"next" -> min(page + 1, pageCount - 1)
				"last" -> pageCount - 1
				else -> {
					item.deferEdit().queue()
					customReactions?.handleComponent(collector, item, user, page, pageCount)
					return
				}
			}

			if (page != oldPage) {
				item.editMessage(render(all.subList(page * pageSize, min(all.size, (page * pageSize) + pageSize)), page, pageCount)).setActionRows(rows).queue()
			} else {
				item.deferEdit().queue() // TODO disable the buttons when they actually don't do anything
			}
		}

		override fun onRemove(item: ComponentInteraction, user: User?) {}

		override fun onDispose(item: ComponentInteraction, user: User?) {}

		override fun onEnd(collected: Map<Any, ComponentInteraction>, reason: CollectorEndReason) {
			customReactions?.onEnd(collected, reason)
			if (reason == CollectorEndReason.IDLE) {
				collector.message?.finalizeComponents(emptySet())
			}
		}
	}
}

interface PaginatorCustomComponents<T> {
	fun modifyComponents(rows: MutableList<ActionRow>)
	fun handleComponent(collector: MessageComponentInteractionCollector, item: ComponentInteraction, user: User?, page: Int, pageCount: Int)
	fun onEnd(collected: Map<Any, ComponentInteraction>, reason: CollectorEndReason)
}