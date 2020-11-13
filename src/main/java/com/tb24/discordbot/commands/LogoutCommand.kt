package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder

class LogoutCommand : BrigadierCommand("logout", "Logs out the Epic account.", arrayOf("o", "signout")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			it.source.ensureSession()
			it.source.session.logout(it.source.message)
			Command.SINGLE_SUCCESS
		}
}