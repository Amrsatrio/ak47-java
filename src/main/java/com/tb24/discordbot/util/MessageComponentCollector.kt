@file:Suppress("NOTHING_TO_INLINE")

package com.tb24.discordbot.util

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageChannel
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.InteractionType
import net.dv8tion.jda.api.interactions.components.Component
import net.dv8tion.jda.api.interactions.components.ComponentInteraction
import java.util.concurrent.CompletableFuture

open class MessageComponentCollectorOptions : CollectorOptions() {
	var componentType: Component.Type? = null
	var max: Int = Int.MAX_VALUE
	var maxComponents: Int = Int.MAX_VALUE
	var maxUsers: Int = Int.MAX_VALUE

	fun toInteractionCollectorOptions() = InteractionCollectorOptions().also {
		it.time = time
		it.idle = idle
		it.dispose = dispose

		it.componentType = componentType
		it.max = max
		it.maxInteractions = maxComponents
		it.maxUsers = maxUsers

		it.interactionType = InteractionType.COMPONENT
	}
}

typealias MessageComponentInteractionCollector = InteractionCollector<ComponentInteraction>

inline fun Message.createMessageComponentCollector(noinline filter: CollectorFilter<ComponentInteraction>, options: MessageComponentCollectorOptions = MessageComponentCollectorOptions()) =
	InteractionCollector(jda, filter, options.toInteractionCollectorOptions().also { it.message = this })

inline fun MessageChannel.createMessageComponentCollector(noinline filter: CollectorFilter<ComponentInteraction>, options: MessageComponentCollectorOptions = MessageComponentCollectorOptions()) =
	InteractionCollector(jda, filter, options.toInteractionCollectorOptions().also { it.channel = this })

class AwaitMessageComponentOptions : MessageComponentCollectorOptions() {
	var errors: Array<CollectorEndReason>? = null
	var finalizeComponentsOnEnd = true
}

@Throws(CollectorException::class)
fun Message.awaitMessageComponent(filter: CollectorFilter<ComponentInteraction>, options: AwaitMessageComponentOptions = AwaitMessageComponentOptions()): CompletableFuture<Collection<ComponentInteraction>> {
	val future = CompletableFuture<Collection<ComponentInteraction>>()
	val collector = createMessageComponentCollector(filter, options)
	collector.callback = object : CollectorListener<ComponentInteraction> {
		override fun onCollect(item: ComponentInteraction, user: User?) {}
		override fun onRemove(item: ComponentInteraction, user: User?) {}
		override fun onDispose(item: ComponentInteraction, user: User?) {}

		override fun onEnd(collected: Map<Any, ComponentInteraction>, reason: CollectorEndReason) {
			if (options.errors?.contains(reason) == true) {
				future.completeExceptionally(CollectorException(collector, reason))
				finalizeComponents(collected.values.map { it.componentId })
			} else {
				future.complete(collected.values)
				if (options.finalizeComponentsOnEnd) {
					finalizeComponents(collected.values.map { it.componentId })
				}
			}
		}
	}
	return future
}