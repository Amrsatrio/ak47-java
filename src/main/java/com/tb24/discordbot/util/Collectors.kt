package com.tb24.discordbot.util

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageReaction
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.channel.text.TextChannelDeleteEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionRemoveEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveAllEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.schedule

abstract class Collector<T>(val client: JDA, open val filter: (T, User?, Map<Any, T>) -> Boolean, open val options: CollectorOptions) {
	var callback: CollectorCallback<T>? = null
	val collected = mutableMapOf<Any, T>()
	var ended = false
	protected val timer = Timer()
	protected var _timeout: TimerTask? = null
	protected var _idletimeout: TimerTask? = null

	init {
		if (options.time != null && options.time!! > 0) _timeout = timer.schedule(options.time!!) { stop(CollectorEndReason.TIME) }
		if (options.idle != null && options.idle!! > 0) _idletimeout = timer.schedule(options.idle!!) { stop(CollectorEndReason.IDLE) }
	}

	fun handleCollect(item: T, user: User?) {
		val collect = collect(item, user)

		if (collect != null && filter(item, user, collected)) {
			collected[collect] = item
			onCollect(item, user)

			if (_idletimeout != null) {
				_idletimeout!!.cancel()
				_idletimeout = timer.schedule(options.idle!!) { stop(CollectorEndReason.IDLE) }
			}
		}
		checkEnd()
	}

	open fun onCollect(item: T, user: User?) {
		callback?.onCollect(item, user)
	}

	fun handleDispose(item: T, user: User?) {
		if (!options.dispose) return

		val dispose = dispose(item, user)
		if (dispose == null || !filter(item, user, collected) || !collected.containsKey(dispose)) return
		collected.remove(dispose)

		callback?.onDispose(item, user)
		checkEnd()
	}

	open fun stop(reason: CollectorEndReason = CollectorEndReason.USER) {
		if (ended) return

		if (_timeout != null) {
			_timeout!!.cancel()
			_timeout = null
		}
		if (_idletimeout != null) {
			_idletimeout!!.cancel()
			_idletimeout = null
		}
		ended = true
		callback?.onEnd(collected, reason)
	}

	fun checkEnd() {
		val reason = endReason()
		if (reason != null) stop(reason)
	}

	abstract fun collect(item: T, user: User?): Any?

	abstract fun dispose(item: T, user: User?): Any?

	abstract fun endReason(): CollectorEndReason?

	interface CollectorCallback<T> {
		fun onCollect(item: T, user: User?)
		fun onRemove(item: T, user: User?)
		fun onDispose(item: T, user: User?)
		fun onEnd(collected: Map<Any, T>, reason: CollectorEndReason)
	}
}

class ReactionCollector(val message: Message, filter: (MessageReaction, User?, Map<Any, MessageReaction>) -> Boolean, options: ReactionCollectorOptions) : Collector<MessageReaction>(message.jda, filter, options) {
	val users = mutableMapOf<Long, User>()
	var total = 0
	private var listener: ListenerAdapter

	init {
		listener = object : ListenerAdapter() {
			override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
				handleCollect(event.reaction, event.user)
			}

			override fun onGuildMessageReactionRemove(event: GuildMessageReactionRemoveEvent) {
				handleDispose(event.reaction, event.user)
			}

			override fun onMessageReactionRemoveAll(event: MessageReactionRemoveAllEvent) {
				empty()
			}

			override fun onMessageDelete(event: MessageDeleteEvent) {
				if (event.messageIdLong == message.idLong) {
					stop(CollectorEndReason.MESSAGE_DELETE)
				}
			}

			override fun onTextChannelDelete(event: TextChannelDeleteEvent) {
				if (event.channel.idLong == message.channel.idLong) {
					stop(CollectorEndReason.CHANNEL_DELETE)
				}
			}
		}
		client.addEventListener(listener)
	}

	override fun onCollect(item: MessageReaction, user: User?) {
		super.onCollect(item, user)
		total++
		if (user != null) users[user.idLong] = user
	}

	private fun onRemove(item: MessageReaction, user: User?) {
		callback?.onRemove(item, user)
		total--
		if (user != null) users.remove(user.idLong)
	}

	override fun stop(reason: CollectorEndReason) {
		super.stop(reason)
		client.removeEventListener(listener)
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
		return if (item.count > 0) null else item.key()
	}

	fun empty() {
		total = 0
		collected.clear()
		users.clear()
		checkEnd()
	}

	override fun endReason(): CollectorEndReason? {
		options as ReactionCollectorOptions
		return when {
			options.max != null && options.max!! > 0 && total >= options.max!! -> CollectorEndReason.LIMIT
			options.maxEmojis != null && options.maxEmojis!! > 0 && collected.size >= options.maxEmojis!! -> CollectorEndReason.EMOJI_LIMIT
			options.maxUsers != null && options.maxUsers!! > 0 && users.size >= options.maxUsers!! -> CollectorEndReason.USER_LIMIT
			else -> null
		}
	}
}

open class CollectorOptions {
	var time: Long? = null
	var idle: Long? = null
	var dispose: Boolean = false
}

open class ReactionCollectorOptions : CollectorOptions() {
	var max: Int? = null
	var maxEmojis: Int? = null
	var maxUsers: Int? = null
}

class AwaitReactionsOptions : ReactionCollectorOptions() {
	var errors: Array<CollectorEndReason>? = null
}

fun Message.createReactionCollector(filter: (MessageReaction, User?, Map<Any, MessageReaction>) -> Boolean, options: ReactionCollectorOptions = ReactionCollectorOptions()): ReactionCollector =
	ReactionCollector(this, filter, options)

@Throws(CollectorException::class)
fun Message.awaitReactions(filter: (MessageReaction, User?, Map<Any, MessageReaction>) -> Boolean, options: AwaitReactionsOptions = AwaitReactionsOptions()): CompletableFuture<Map<Any, MessageReaction>>  {
	val future = CompletableFuture<Map<Any, MessageReaction>>()
	val collector = createReactionCollector(filter, options)
	collector.callback = object : Collector.CollectorCallback<MessageReaction> {
		override fun onCollect(item: MessageReaction, user: User?) {}

		override fun onRemove(item: MessageReaction, user: User?) {}

		override fun onDispose(item: MessageReaction, user: User?) {}

		override fun onEnd(collected: Map<Any, MessageReaction>, reason: CollectorEndReason) {
			if (options.errors != null && options.errors!!.contains(reason)) future.completeExceptionally(CollectorException(collector, reason))
			else future.complete(collected)
		}
	}
	return future
}

enum class CollectorEndReason {
	TIME,
	IDLE,
	USER,
	LIMIT,
	EMOJI_LIMIT,
	USER_LIMIT,
	MESSAGE_DELETE,
	CHANNEL_DELETE
}

class CollectorException(val collector: Collector<*>, val reason: CollectorEndReason) : Exception()

private fun MessageReaction.key() = if (reactionEmote.isEmote) reactionEmote.idLong else reactionEmote.emoji