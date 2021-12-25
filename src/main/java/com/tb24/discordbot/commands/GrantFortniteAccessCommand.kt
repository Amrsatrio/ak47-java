package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.exec

class GrantFortniteAccessCommand : BrigadierCommand("fortniteaccess", "Checks your access to Fortnite and grants you if needed.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes { execute(it) }

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.api.fortniteService.requestAccess(source.api.currentLoggedIn.id).exec()
		source.complete("✅ You now have access to Fortnite services")
		return Command.SINGLE_SUCCESS
	}
}