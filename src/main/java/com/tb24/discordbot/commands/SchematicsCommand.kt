package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.getEmoteByName
import com.tb24.discordbot.util.replyPaginated
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.util.format
import net.dv8tion.jda.api.MessageBuilder

class SchematicsCommand : BrigadierCommand("schematics", "Lists your or a given user's schematics.", arrayOf("schems")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::display, "Getting schematics")

	private fun display(source: CommandSourceStack, campaign: McpProfile): Int {
		source.ensureCompletedCampaignTutorial(campaign)
		val schems = campaign.items.values.filter { it.primaryAssetType == "Schematic" }.sortedWith { a, b ->
			val rating1 = a.powerLevel
			val rating2 = b.powerLevel
			if (rating1 == rating2) {
				a.displayName.compareTo(b.displayName)
			} else {
				rating2.compareTo(rating1)
			}
		}
		source.replyPaginated(schems, 6) { content, page, pageCount ->
			val embed = source.createEmbed(campaign.owner)
				.setTitle("Schematics")
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			for (schem in content) {
				val alterations = mutableListOf<String>()
				schem.attributes.getAsJsonArray("alterations")?.let {
					for (alterationId in it) {
						val alterationItem = FortItemStack(alterationId.asString, 1)
						val alteration = alterationItem.defData
						if (alteration == null) {
							alterations.add(alterationId.asString)
						} else {
							alterations.add(getEmoteByName(alterationItem.rarity.name.toLowerCase() + '2')?.asMention + ' ' + (alteration.Description.format() ?: alteration.DisplayName.format() ?: alterationId.asString))
						}
					}
				}
				embed.addField("Lv%,d %s".format(schem.attributes["level"]?.asInt ?: 0, schem.displayName.trim()), alterations.joinToString("\n"), true)
			}
			MessageBuilder(embed)
		}
		return Command.SINGLE_SUCCESS
	}
}