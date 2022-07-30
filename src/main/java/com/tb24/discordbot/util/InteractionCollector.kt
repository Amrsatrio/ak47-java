package com.tb24.discordbot.util

import com.tb24.discordbot.util.CollectorEndReason.*
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.InteractionType
import net.dv8tion.jda.api.interactions.components.Component
import net.dv8tion.jda.api.interactions.components.ComponentInteraction

open class InteractionCollectorOptions : CollectorOptions() {
	var channel: MessageChannel? = null
	var componentType: Component.Type? = null
	var guild: Guild? = null
	var interactionType: InteractionType? = null
	var max: Int = Int.MAX_VALUE
	var maxInteractions: Int = Int.MAX_VALUE
	var maxUsers: Int = Int.MAX_VALUE
	var message: Message? = null
	var interaction: Interaction? = null
}

class InteractionCollector<T : Interaction>(client: JDA, filter: CollectorFilter<T>, options: InteractionCollectorOptions) : Collector<T, InteractionCollectorOptions>(client, filter, options) {
	val message = options.message
	val messageInteraction = options.interaction
	val channel = options.interaction?.channel ?: options.message?.channel ?: options.channel
	val guild = options.interaction?.guild ?: options.message?.guild ?: (options.channel as? TextChannel)?.guild ?: options.guild
	private val interactionType = options.interactionType
	private val componentType = options.componentType
	private val users = mutableMapOf<Long, User>()
	private var total = 0

	init {
		client.addEventListener(this)
	}

	// region ListenerAdapter interface
	override fun onMessageDelete(event: MessageDeleteEvent) {
		if (message != null && event.messageIdLong == message.idLong) {
			stop(MESSAGE_DELETE)
		}
		/*if (messageInteraction != null && event.message.interaction?.idLong == messageInteractionId) {
			stop(MESSAGE_DELETE)
		}*/
	}

	override fun onMessageBulkDelete(event: MessageBulkDeleteEvent) {
		if (message != null && message.toString() in event.messageIds) {
			stop(MESSAGE_DELETE)
		}
	}

	override fun onChannelDelete(event: ChannelDeleteEvent) {
		if (channel == null) {
			return
		}
		if (event.channel is ThreadChannel) {
			if (event.channel.idLong == channel.idLong) {
				stop(THREAD_DELETE)
			}
		} else if (event.channel.idLong == channel.idLong || (event.channel as? IThreadContainer)?.threadChannels?.any { it.idLong == channel.idLong } == true) {
			stop(CHANNEL_DELETE)
		}
	}

	override fun onGuildLeave(event: GuildLeaveEvent) {
		if (guild != null && event.guild.idLong == guild.idLong) {
			stop(GUILD_DELETE)
		}
	}

	override fun onGenericInteractionCreate(event: GenericInteractionCreateEvent) {
		@Suppress("UNCHECKED_CAST")
		handleCollect(event.interaction as T, event.user)
	}
	// endregion

	// region Collector interface
	override fun onCollect(item: T, user: User?) {
		super.onCollect(item, user)
		total++
		if (user != null) users[user.idLong] = user
	}

	override fun collect(item: T, user: User?) = when {
		interactionType != null && item.type != interactionType -> null
		componentType != null && item is ComponentInteraction && item.componentType != componentType -> null
		message != null && item.message?.idLong != message.idLong -> null
		messageInteraction != null && item.message?.interaction?.idLong != messageInteraction.idLong -> null
		channel != null && item.channel?.idLong != channel.idLong -> null
		guild != null && item.guild?.idLong != guild.idLong -> null
		else -> item.idLong
	}

	override fun dispose(item: T, user: User?) = collect(item, user) // Same conditions

	override fun endReason() = when {
		total >= options.max -> LIMIT
		collected.size == options.maxInteractions -> INTERACTION_LIMIT
		users.size >= options.maxUsers -> USER_LIMIT
		else -> null
	}
	// endregion

	fun empty() {
		total = 0
		collected.clear()
		users.clear()
		checkEnd()
	}
}