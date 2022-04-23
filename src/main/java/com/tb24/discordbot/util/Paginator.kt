package com.tb24.discordbot.util

import com.tb24.discordbot.commands.CommandSourceStack
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.ComponentInteraction
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import net.dv8tion.jda.api.interactions.components.selections.SelectMenu
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class Paginator<T>(
	val source: CommandSourceStack,
	private val all: List<T>,
	private val pageSize: Int,
	initialPage: Int = 0,
	private val customComponents: PaginatorCustomComponents<T>? = null,
	private val render: (content: List<T>, page: Int, pageCount: Int) -> MessageBuilder
) : CollectorListener<ComponentInteraction> {
	var page = initialPage
		private set
	val pageCount = ceil(all.size / pageSize.toFloat()).toInt()
	private val builder = render(all.subList(page * pageSize, min(all.size, (page * pageSize) + pageSize)), page, pageCount)
	private var actionRows = emptyList<ActionRow>()
	private lateinit var collector: MessageComponentInteractionCollector

	init {
		val msg = source.complete(builder.setActionRows(buildRows()).build())
		if (!source.unattended && (pageCount > 1 || customComponents != null)) {
			collector = msg.createMessageComponentInteractionCollector({ _, user, _ -> user?.idLong == source.author.idLong }, MessageComponentInteractionCollectorOptions().apply {
				idle = 90000L
				//dispose = true
			})
			collector.callback = this
		}
	}

	private fun buildRows(): List<ActionRow> {
		val rows = mutableListOf<ActionRow>()
		if (pageCount > 1) {
			rows.add(
				ActionRow.of(
					Button.secondary("first", "⏮").withDisabled(page == 0),
					Button.secondary("prev", "◀").withDisabled(page == 0),
					Button.secondary("next", "▶").withDisabled(page == pageCount - 1),
					Button.secondary("last", "⏭").withDisabled(page == pageCount - 1)
				)
			)
		}
		customComponents?.modifyComponents(this, rows)
		actionRows = rows
		return rows
	}

	fun stop() {
		collector.stop()
	}

	fun stopAndFinalizeComponents(selectedIds: Collection<String>) {
		stop()
		collector.message?.editMessageComponents(actionRows.map { row ->
			ActionRow.of(*row.components.map {
				when (it) {
					is Button -> it.withStyle(if (it.id in selectedIds) ButtonStyle.SUCCESS else ButtonStyle.SECONDARY).asDisabled()
					is SelectMenu -> it.asDisabled()
					else -> throw AssertionError()
				}
			}.toTypedArray())
		})?.queue()
	}

	// region CollectorListener interface
	override fun onCollect(item: ComponentInteraction, user: User?) {
		val oldPage = page
		page = when (item.componentId) {
			"first" -> 0
			"prev" -> max(page - 1, 0)
			"next" -> min(page + 1, pageCount - 1)
			"last" -> pageCount - 1
			else -> {
				item.deferEdit().queue()
				customComponents?.handleComponent(this, item, user)
				return
			}
		}

		if (page != oldPage) {
			item.editMessage(render(all.subList(page * pageSize, min(all.size, (page * pageSize) + pageSize)), page, pageCount).build()).setActionRows(buildRows()).queue()
		} else {
			item.deferEdit().queue()
		}
	}

	override fun onRemove(item: ComponentInteraction, user: User?) {}

	override fun onDispose(item: ComponentInteraction, user: User?) {}

	override fun onEnd(collected: Map<Any, ComponentInteraction>, reason: CollectorEndReason) {
		customComponents?.onEnd(collected, reason)
		if (reason == CollectorEndReason.IDLE) {
			collector.message?.finalizeComponents(emptySet())
		}
	}
	// endregion
}

fun <T> CommandSourceStack.replyPaginated(
	all: List<T>,
	pageSize: Int,
	initialPage: Int = 0,
	customComponents: PaginatorCustomComponents<T>? = null,
	render: (content: List<T>, page: Int, pageCount: Int) -> MessageBuilder
) = Paginator(this, all, pageSize, initialPage, customComponents, render)

interface PaginatorCustomComponents<T> {
	fun modifyComponents(paginator: Paginator<T>, rows: MutableList<ActionRow>)
	fun handleComponent(paginator: Paginator<T>, item: ComponentInteraction, user: User?)
	fun onEnd(collected: Map<Any, ComponentInteraction>, reason: CollectorEndReason)
}