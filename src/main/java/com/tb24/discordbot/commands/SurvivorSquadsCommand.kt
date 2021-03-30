package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.QueryProfile

class SurvivorSquadsCommand : BrigadierCommand("squads", "Manages your survivor squads.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val homebase = source.session.getHomebase(source.api.currentLoggedIn.id)
		val map = Array(8) { Array(8) { "" } }

		return Command.SINGLE_SUCCESS
	}

	class SavedSquadPresets(@JvmField var id: String = "") {
		@JvmField var presets: List<Array<Array<String>>> = emptyList()
	}
}