package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder

class WhoAmICommand : BrigadierCommand("whoami", "Shows the display name and account ID of the account you are currently logged in as.", arrayOf("wai")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.complete("%s (%s)".format(source.api.currentLoggedIn.displayName, source.api.currentLoggedIn.id))
		return Command.SINGLE_SUCCESS
	}
}