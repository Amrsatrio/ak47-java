package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.exec

class GrantFortniteAccessCommand : BrigadierCommand("fortniteaccess", "Checks your access to Fortnite and grants you if needed.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.api.fortniteService.requestAccess(source.api.currentLoggedIn.id).exec()
			Command.SINGLE_SUCCESS
		}
}