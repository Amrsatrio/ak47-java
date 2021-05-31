package com.tb24.discordbot.util

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.hooks.ListenerAdapter
import java.util.*
import kotlin.concurrent.schedule

typealias CollectorFilter<T> = (T, User?, Map<Any, T>) -> Boolean

open class CollectorOptions {
	var time: Long? = null
	var idle: Long? = null
	var dispose: Boolean = false
}

enum class CollectorEndReason {
	TIME,
	IDLE,
	USER,
	LIMIT,
	COMPONENT_LIMIT,
	EMOJI_LIMIT,
	PROCESSED_LIMIT,
	USER_LIMIT,
	MESSAGE_DELETE,
	CHANNEL_DELETE
}

class CollectorException(val collector: Collector<*, *>, val reason: CollectorEndReason) : Exception()

interface CollectorListener<T> {
	fun onCollect(item: T, user: User?)
	fun onRemove(item: T, user: User?)
	fun onDispose(item: T, user: User?)
	fun onEnd(collected: Map<Any, T>, reason: CollectorEndReason)
}

abstract class Collector<ItemType, OptionsType : CollectorOptions>(val client: JDA, open val filter: CollectorFilter<ItemType>, val options: OptionsType) : ListenerAdapter() {
	var callback: CollectorListener<ItemType>? = null
	val collected = mutableMapOf<Any, ItemType>()
	var ended = false
	protected val timer = Timer()
	protected var _timeout: TimerTask? = null
	protected var _idletimeout: TimerTask? = null

	init {
		if (options.time != null && options.time!! > 0) _timeout = timer.schedule(options.time!!) { stop(CollectorEndReason.TIME) }
		if (options.idle != null && options.idle!! > 0) _idletimeout = timer.schedule(options.idle!!) { stop(CollectorEndReason.IDLE) }
	}

	fun handleCollect(item: ItemType, user: User?) {
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

	open fun onCollect(item: ItemType, user: User?) {
		callback?.onCollect(item, user)
	}

	open fun onRemove(item: ItemType, user: User?) {
		callback?.onRemove(item, user)
	}

	fun handleDispose(item: ItemType, user: User?) {
		if (!options.dispose) return

		val dispose = dispose(item, user)
		if (dispose == null || !filter(item, user, collected) || !collected.containsKey(dispose)) return
		collected.remove(dispose)

		callback?.onDispose(item, user)
		checkEnd()
	}

	fun stop(reason: CollectorEndReason = CollectorEndReason.USER) {
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
		client.removeEventListener(this)
		callback?.onEnd(collected, reason)
	}

	fun checkEnd() {
		val reason = endReason()
		if (reason != null) stop(reason)
	}

	abstract fun collect(item: ItemType, user: User?): Any?
	abstract fun dispose(item: ItemType, user: User?): Any?
	abstract fun endReason(): CollectorEndReason?
}