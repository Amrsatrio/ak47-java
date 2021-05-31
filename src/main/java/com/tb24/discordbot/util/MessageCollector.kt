package com.tb24.discordbot.util

import com.tb24.discordbot.util.CollectorEndReason.*
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageChannel
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.channel.text.TextChannelDeleteEvent
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.util.concurrent.CompletableFuture

open class MessageCollectorOptions : CollectorOptions() {
	var max: Int? = null
	var maxProcessed: Int? = null
}

class MessageCollector : Collector<Message, MessageCollectorOptions> {
	val channel: MessageChannel
	var received = 0

	constructor(channel: MessageChannel, filter: CollectorFilter<Message>, options: MessageCollectorOptions) : super(channel.jda, filter, options) {
		this.channel = channel
		client.addEventListener(this)
	}

	// region ListenerAdapter interface
	override fun onMessageReceived(event: MessageReceivedEvent) {
		handleCollect(event.message, event.author)
	}

	override fun onMessageDelete(event: MessageDeleteEvent) {
		val message = collected[event.messageIdLong] ?: return
		handleDispose(message, message.author)
	}

	override fun onMessageBulkDelete(event: MessageBulkDeleteEvent) {
		for (messageId in event.messageIds) {
			val message = collected[messageId.toLong()] ?: continue
			handleDispose(message, message.author)
		}
	}

	override fun onTextChannelDelete(event: TextChannelDeleteEvent) {
		if (event.channel.idLong == channel.idLong) {
			stop(CHANNEL_DELETE)
		}
	}
	// endregion

	// region Collector interface
	override fun collect(item: Message, user: User?): Any? {
		if (item.channel.idLong != channel.idLong) return null
		received++
		return item.idLong
	}

	override fun dispose(item: Message, user: User?) =
		if (item.channel.idLong == channel.idLong) item.idLong else null

	override fun endReason() = when {
		options.max != null && collected.size >= options.max!! -> LIMIT
		options.maxProcessed != null && received == options.maxProcessed -> PROCESSED_LIMIT
		else -> null
	}
	// endregion
}

inline fun MessageChannel.createMessageCollector(noinline filter: CollectorFilter<Message>, options: MessageCollectorOptions = MessageCollectorOptions()) =
	MessageCollector(this, filter, options)

class AwaitMessagesOptions : MessageCollectorOptions() {
	var errors: Array<CollectorEndReason>? = null
}

@Throws(CollectorException::class)
fun MessageChannel.awaitMessages(filter: CollectorFilter<Message>, options: AwaitMessagesOptions = AwaitMessagesOptions()): CompletableFuture<Collection<Message>> {
	val future = CompletableFuture<Collection<Message>>()
	val collector = createMessageCollector(filter, options)
	collector.callback = object : CollectorListener<Message> {
		override fun onCollect(item: Message, user: User?) {}
		override fun onRemove(item: Message, user: User?) {}
		override fun onDispose(item: Message, user: User?) {}

		override fun onEnd(collected: Map<Any, Message>, reason: CollectorEndReason) {
			if (options.errors?.contains(reason) == true) future.completeExceptionally(CollectorException(collector, reason))
			else future.complete(collected.values)
		}
	}
	return future
}