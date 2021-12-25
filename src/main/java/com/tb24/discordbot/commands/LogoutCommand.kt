package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder

class LogoutCommand : BrigadierCommand("logout", "Logs out the Epic account.", arrayOf("o", "signout")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.session.logout(source)
			Command.SINGLE_SUCCESS
		}

	override fun getSlashCommand() = newCommandBuilder()
		.executes { source ->
			source.ensureSession()
			source.session.logout(source)
			Command.SINGLE_SUCCESS
		}
}