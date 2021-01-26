package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.util.replyPaginated
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.util.format
import net.dv8tion.jda.api.MessageBuilder

class SchematicsCommand : BrigadierCommand("schematics", "Lists the given user's schematics.", arrayOf("schems")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::display, "Getting schematics")

	private fun display(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		val schems = campaign.items.values.filter { it.primaryAssetType.equals("Schematic", true) }.sortedWith { a, b ->
			val levelA = a.attributes["level"]?.asInt ?: 0
			val levelB = b.attributes["level"]?.asInt ?: 0
			if (levelA == levelB) {
				a.displayName.compareTo(b.displayName)
			} else {
				levelB - levelA
			}
		}
		source.message.replyPaginated(schems, 6, source.loadingMsg) { content, page, pageCount ->
			val embed = source.createEmbed(campaign.owner)
				.setTitle("Schematics")
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			for (schem in content) {
				val alterations = mutableListOf<String>()
				schem.attributes.getAsJsonArray("alterations")?.let {
					for (alterationId in it) {
						val alteration = FortItemStack(alterationId.asString, 1).defData
						if (alteration == null) {
							alterations.add(alterationId.asString)
						} else {
							alterations.add(alteration.Description.format() ?: alteration.DisplayName.format() ?: alterationId.asString)
						}
					}
				}
				embed.addField("%s (Level %,d)".format(schem.displayName.trim(), schem.attributes["level"]?.asInt ?: 0), alterations.joinToString("\n"), true)
			}
			MessageBuilder(embed).build()
		}
		return Command.SINGLE_SUCCESS
	}
}