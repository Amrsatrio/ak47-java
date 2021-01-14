package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.exec

class IslandsCommand : BrigadierCommand("islands", "Manage your creative islands history and favorites.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			val data = source.api.fortniteService.queryCreativeHistory(source.api.currentLoggedIn.id, null, null).exec().body()!!
			Command.SINGLE_SUCCESS
		}
}