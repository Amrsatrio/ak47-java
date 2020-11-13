package com.tb24.discordbot.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode

abstract class BrigadierCommand @JvmOverloads constructor(val name: String, val description: String, val aliases: Array<String> = emptyArray()) {
	lateinit var registeredNode: CommandNode<CommandSourceStack>

	abstract fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack>

	open fun register(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralCommandNode<CommandSourceStack> =
		dispatcher.register(getNode(dispatcher))

	protected inline fun newRootNode() = literal(name)

	protected inline fun literal(name: String): LiteralArgumentBuilder<CommandSourceStack> =
		LiteralArgumentBuilder.literal(name)

	protected inline fun <T> argument(name: String, type: ArgumentType<T>): RequiredArgumentBuilder<CommandSourceStack, T> =
		RequiredArgumentBuilder.argument(name, type)
}