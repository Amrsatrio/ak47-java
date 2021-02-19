package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.textureEmote
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.attributes.CampaignProfileAttributes
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import me.fungames.jfortniteparse.fort.enums.EFortStatType.*
import java.text.DateFormat

class CampaignOverviewCommand : BrigadierCommand("stw", "Shows campaign statistics of an account.", arrayOf("profile")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Loading profile")

	private fun execute(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		val attrs = campaign.stats.attributes as CampaignProfileAttributes
		val quests = arrayOf(
			"Quest:achievement_destroygnomes",
			"Quest:achievement_savesurvivors",
			"Quest:achievement_loottreasurechests",
			"Quest:achievement_playwithothers",
			"Quest:achievement_buildstructures",
			"Quest:achievement_killmistmonsters",
			"Quest:achievement_explorezones"
		)
		val questItems = hashMapOf<String, FortItemStack>()
		var researchPoints = 0
		val mythicSchematics = mutableListOf<FortItemStack>()
		for (item in campaign.items.values) {
			when {
				item.templateId == "Token:collectionresource_nodegatetoken01" -> researchPoints += item.quantity
				item.templateId.contains("stormking_sr") -> mythicSchematics.add(item)
				quests.any { it == item.templateId } -> questItems[item.templateId] = item
			}
		}
		val embed = source.createEmbed(campaign.owner)
			.setDescription("**Commander Level:** %,d\n**Days Logged in:** %,d\n**Homebase Name:** %s"
				.format(attrs.level, attrs.daily_rewards?.totalDaysLoggedIn ?: 0, "[PH] TODO"))
		embed.addField("Achievements", quests.joinToString("\n") { questTemplateId ->
			val questItem = campaign.items.values.firstOrNull { it.templateId == questTemplateId }
				?: FortItemStack(questTemplateId, 1)
			val (completion, max) = getQuestCompletion(questItem)
			"%s **%s:** %,d/%,d (%s)".format(
				textureEmote(questItem.defData.LargePreviewImage.toString())?.asMention,
				questItem.displayName,
				completion, max,
				Formatters.percentZeroFraction.format(completion.toDouble() / max.toDouble())
			)
		}, true)
		val sb = StringBuilder()
		for (statType in arrayOf(Fortitude, Offense, Resistance, Technology)) {
			sb.append("%s **%s:** Lv %,d\n".format(textureEmote(statType.icon)?.asMention, statType.displayName.format(), attrs.research_levels[statType]))
		}
		sb.append("%s **Stored Research:** %,d".format(textureEmote("/Game/UI/Foundation/Textures/Icons/Currency/T-Icon-ResearchPoint-128.T-Icon-ResearchPoint-128")?.asMention, researchPoints))
		embed.addField("Research", sb.toString(), true)
		embed.addField("Collection Book", "**Level:** %,d\n**Unslot Cost:** %s %,d".format(
			attrs.collection_book.maxBookXpLevelAchieved,
			Utils.MTX_EMOJI,
			attrs.unslot_mtx_spend
		), true)
		val df = DateFormat.getDateInstance()
		embed.addField("Dates", "**Creation Date:** %s\n**Last Updated:** %s".format(
			df.format(campaign.created),
			df.format(campaign.updated)
		), true)
		embed.addField("Miscellaneous", "**Mythic Schematics:** %,d\n**Revisions:** %,d\n**Zones completed:** %,d".format(
			mythicSchematics.size,
			campaign.rvn,
			attrs.gameplay_stats?.firstOrNull { it.statName == "zonescompleted" }?.statValue?.toIntOrNull() ?: 0
		), true)
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}