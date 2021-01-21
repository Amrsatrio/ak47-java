package com.tb24.discordbot.util

import com.tb24.discordbot.util.CollectorEndReason.*
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageReaction
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.channel.text.TextChannelDeleteEvent
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

	override fun onTextChannelDelete(event: TextChannelDeleteEvent) {
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
	// endregion

	fun empty() {
		total = 0
		collected.clear()
		users.clear()
		checkEnd()
	}

	override fun endReason() = when {
		options.max != null && options.max!! > 0 && total >= options.max!! -> LIMIT
		options.maxEmojis != null && options.maxEmojis!! > 0 && collected.size >= options.maxEmojis!! -> EMOJI_LIMIT
		options.maxUsers != null && options.maxUsers!! > 0 && users.size >= options.maxUsers!! -> USER_LIMIT
		else -> null
	}

	private inline fun MessageReaction.key() = reactionEmote.run { if (isEmote) idLong else emoji }
}

inline fun Message.createReactionCollector(noinline filter: CollectorFilter<MessageReaction>, options: ReactionCollectorOptions = ReactionCollectorOptions()): ReactionCollector =
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
			if (options.errors != null && reason in options.errors!!) future.completeExceptionally(CollectorException(collector, reason))
			else future.complete(collected.values)
		}
	}
	return future
}