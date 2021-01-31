package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder

class CraftCommand : BrigadierCommand("craft", "Crafts an item into your backpack.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	private fun execute(source: CommandSourceStack): Int {
		return Command.SINGLE_SUCCESS
	}
}