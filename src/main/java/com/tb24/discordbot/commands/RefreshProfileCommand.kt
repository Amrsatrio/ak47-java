package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.BoolArgumentType.getBool
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin

class RefreshProfileCommand : BrigadierCommand("refresh", "Refreshes your BR or STW profile (including quests).", arrayOf("clientquestlogin")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes(::execute)
		.then(argument("STW?", bool())
			.executes { execute(it, getBool(it, "STW?")) }
		)

	private fun execute(context: CommandContext<CommandSourceStack>, stw: Boolean = false): Int {
		val source = context.source
		source.ensureSession()
		source.loading("Refreshing profile")
		val response = source.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), if (stw) "campaign" else "athena").await()
		if (response.profileRevision > response.profileChangesBaseRevision) {
			source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
				.setTitle("✅ Refreshed")
				.build())
		} else {
			source.complete(null, source.createEmbed().setColor(COLOR_ERROR)
				.setTitle("❌ Nothing refreshed")
				.build())
		}
		return Command.SINGLE_SUCCESS
	}
}