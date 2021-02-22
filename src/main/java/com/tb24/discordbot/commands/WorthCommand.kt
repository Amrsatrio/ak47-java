package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.QueryProfile

class WorthCommand : BrigadierCommand("worth", "Estimates how your account is worth.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		var outfitsCount = 0
		var exclusivesCount = 0
		for (item in athena.items.values) {

		}
		return Command.SINGLE_SUCCESS
	}
}