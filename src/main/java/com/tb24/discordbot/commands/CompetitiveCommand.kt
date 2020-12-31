package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.exec
import com.tb24.fn.util.Formatters

class CompetitiveCommand : BrigadierCommand("comp", "Shows your competitive data", arrayOf("arena")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting competitive data")
			val data = source.api.eventsService.eventDataForAccount("Fortnite", source.api.currentLoggedIn.id).exec().body()!!
			source.complete(null, source.createEmbed()
				.setTitle("Competitive data")
				.addField("Persistent scores", data.persistentScores?.entries
					?.sortedBy { it.key }
					?.joinToString("\n") { it.key + ": " + Formatters.num.format(it.value) }
					?.takeIf { it.isNotEmpty() }
					?: "No entries", false)
				.build())
			Command.SINGLE_SUCCESS
		}
}