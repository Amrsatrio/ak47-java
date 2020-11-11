package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.QueryProfile

class LockerCommand : BrigadierCommand("locker", "kek") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> =newRootNode()
		.executes { c ->
			val source = c .source
			source.ensureSession()
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()

			Command.SINGLE_SUCCESS
		}
}