package com.tb24.discordbot.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.tree.CommandNode

abstract class BrigadierCommand @JvmOverloads constructor(val name: String, val description: String, val aliases: List<String> = emptyList()) {
	lateinit var registeredNode: CommandNode<CommandSourceStack>

	abstract fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack>

	protected fun newRootNode(): LiteralArgumentBuilder<CommandSourceStack> = literal(name)
}