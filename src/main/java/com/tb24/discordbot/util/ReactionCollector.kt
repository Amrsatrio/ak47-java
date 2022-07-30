package com.tb24.discordbot.util

import com.tb24.discordbot.util.CollectorEndReason.*
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageReaction
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.emoji.CustomEmoji
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveAllEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import java.util.concurrent.CompletableFuture

open class ReactionCollectorOptions : CollectorOptions() {
	var max: Int? = null
	var maxEmojis: Int? = null
	var maxUsers: Int? = null
}

class ReactionCollector : Collector<MessageReaction, ReactionCollectorOptions> {
	val message: Message
	val users = mutableMapOf<Long, User>()
	var total = 0

	constructor(message: Message, filter: CollectorFilter<MessageReaction>, options: ReactionCollectorOptions) : super(message.jda, filter, options) {
		this.message = message
		client.addEventListener(this)
	}

	// region ListenerAdapter interface
	override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
		handleCollect(event.reaction, event.user)
	}

	override fun onMessageReactionRemove(event: MessageReactionRemoveEvent) {
		handleDispose(event.reaction, users[event.userIdLong])
	}

	override fun onMessageReactionRemoveAll(event: MessageReactionRemoveAllEvent) {
		empty()
	}

	override fun onMessageDelete(event: MessageDeleteEvent) {
		if (event.messageIdLong == message.idLong) {
			stop(MESSAGE_DELETE)
		}
	}

	override fun onMessageBulkDelete(event: MessageBulkDeleteEvent) {
		if (message.id in event.messageIds) {
			stop(MESSAGE_DELETE)
		}
	}

	override fun onChannelDelete(event: ChannelDeleteEvent) {
		if (event.channel.idLong == message.channel.idLong) {
			stop(CHANNEL_DELETE)
		}
	}
	// endregion

	// region Collector interface
	override fun onCollect(item: MessageReaction, user: User?) {
		super.onCollect(item, user)
		total++
		if (user != null) users[user.idLong] = user
	}

	override fun onRemove(item: MessageReaction, user: User?) {
		super.onRemove(item, user)
		total--
		if (user != null) users.remove(user.idLong)
	}

	override fun collect(item: MessageReaction, user: User?): Any? {
		if (item.messageIdLong != message.idLong) return null
		return item.key()
	}

	override fun dispose(item: MessageReaction, user: User?): Any? {
		if (item.messageIdLong != message.idLong) return null

		if (collected.containsKey(item.key()) && user != null && users.containsKey(user.idLong)) {
			onRemove(item, user)
		}
		return /*if (item.count > 0)*/ null //else item.key() TODO figure out how to get the number of reactions within an event stack
	}

	override fun endReason() = when {
		options.max != null && options.max!! > 0 && total >= options.max!! -> LIMIT
		options.maxEmojis != null && options.maxEmojis!! > 0 && collected.size >= options.maxEmojis!! -> EMOJI_LIMIT
		options.maxUsers != null && options.maxUsers!! > 0 && users.size >= options.maxUsers!! -> USER_LIMIT
		else -> null
	}
	// endregion

	fun empty() {
		total = 0
		collected.clear()
		users.clear()
		checkEnd()
	}

	private inline fun MessageReaction.key() = emoji.run { if (this is CustomEmoji) idLong else name }
}

inline fun Message.createReactionCollector(noinline filter: CollectorFilter<MessageReaction>, options: ReactionCollectorOptions = ReactionCollectorOptions()) =
	ReactionCollector(this, filter, options)

class AwaitReactionsOptions : ReactionCollectorOptions() {
	var errors: Array<CollectorEndReason>? = null
}

@Throws(CollectorException::class)
fun Message.awaitReactions(filter: CollectorFilter<MessageReaction>, options: AwaitReactionsOptions = AwaitReactionsOptions()): CompletableFuture<Collection<MessageReaction>> {
	val future = CompletableFuture<Collection<MessageReaction>>()
	val collector = createReactionCollector(filter, options)
	collector.callback = object : CollectorListener<MessageReaction> {
		override fun onCollect(item: MessageReaction, user: User?) {}
		override fun onRemove(item: MessageReaction, user: User?) {}
		override fun onDispose(item: MessageReaction, user: User?) {}

		override fun onEnd(collected: Map<Any, MessageReaction>, reason: CollectorEndReason) {
			if (options.errors?.contains(reason) == true) future.completeExceptionally(CollectorException(collector, reason))
			else future.complete(collected.values)
		}
	}
	return future
}