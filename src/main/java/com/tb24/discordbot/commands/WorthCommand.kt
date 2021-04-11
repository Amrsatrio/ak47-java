package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.addFieldSeparate
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import me.fungames.jfortniteparse.fort.exports.AthenaCosmeticItemDefinition

class WorthCommand : BrigadierCommand("worth", "Estimates how your account is worth.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val bySource = athena.items.values.groupBy { item ->
			val defData = item.defData as? AthenaCosmeticItemDefinition
			if (defData != null) {
				val sourceTag = defData.GameplayTags?.firstOrNull { it.text.startsWith("Cosmetics.Source.", true) }
				if (sourceTag != null) {
					return@groupBy sourceTag.text.substring("Cosmetics.Source.".length)
				}
			}
			"Unknown"
		}
		source.complete(null, source.createEmbed()
			.setTitle("Account Worth")
			.setDescription("This feature is work in progress. More info will be added later.")
			.addFieldSeparate("Cosmetic Sources", bySource.entries.sortedByDescending { it.value.size }, 0) {
				"%s: %,d".format(it.key, it.value.size)
			}
			.build())
		return Command.SINGLE_SUCCESS
	}
}