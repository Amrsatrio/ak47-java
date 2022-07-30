@file:Suppress("NOTHING_TO_INLINE")

package com.tb24.discordbot.util

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageChannel
import net.dv8tion.jda.api.interactions.InteractionType
import net.dv8tion.jda.api.interactions.components.ComponentInteraction
import java.util.concurrent.CompletableFuture

typealias MessageComponentInteractionCollector = InteractionCollector<ComponentInteraction>

inline fun Message.createMessageComponentCollector(noinline filter: CollectorFilter<ComponentInteraction>, options: InteractionCollectorOptions = InteractionCollectorOptions()) =
	InteractionCollector(jda, filter, options.also {
		it.interactionType = InteractionType.COMPONENT
		it.message = this
	})

inline fun MessageChannel.createMessageComponentCollector(noinline filter: CollectorFilter<ComponentInteraction>, options: InteractionCollectorOptions = InteractionCollectorOptions()) =
	InteractionCollector(jda, filter, options.also {
		it.interactionType = InteractionType.COMPONENT
		it.channel = this
	})

@Throws(CollectorException::class)
fun Message.awaitMessageComponent(filter: CollectorFilter<ComponentInteraction>, options: AwaitInteractionOptions = AwaitInteractionOptions()): CompletableFuture<Collection<ComponentInteraction>> =
	awaitInteraction(filter, options.also {
		it.interactionType = InteractionType.COMPONENT
	}).thenApply { it.filterIsInstance<ComponentInteraction>() }