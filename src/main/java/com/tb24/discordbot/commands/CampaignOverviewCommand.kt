package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.QueryPublicProfile
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.fn.model.mcpprofile.stats.CommonPublicProfileStats
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
		val stats = campaign.stats as CampaignProfileStats
		if (source.api.currentLoggedIn.id == campaign.owner.id) {
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "common_public").await()
		} else {
			source.api.profileManager.dispatchPublicCommandRequest(campaign.owner, QueryPublicProfile(), "common_public").await()
		}
		val homebaseName = (source.api.profileManager.getProfileData(campaign.owner.id, "common_public").stats as CommonPublicProfileStats).homebase_name
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
		var canReceiveMtxCurrency = false
		for (item in campaign.items.values) {
			when {
				item.templateId == "Token:collectionresource_nodegatetoken01" -> researchPoints += item.quantity
				item.templateId.contains("stormking_sr") -> mythicSchematics.add(item)
				item.templateId == "Token:receivemtxcurrency" -> canReceiveMtxCurrency = true
				item.templateId in quests -> questItems[item.templateId] = item
			}
		}
		val embed = source.createEmbed(campaign.owner)
			.setDescription("**Commander Level:** %,d\n**Days Logged in:** %,d\n**Homebase Name:** %s"
				.format(stats.level, stats.daily_rewards?.totalDaysLoggedIn ?: 0, homebaseName))
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
			sb.append("%s **%s:** Lv %,d\n".format(textureEmote(statType.icon)?.asMention, statType.displayName.format(), stats.research_levels[statType]))
		}
		sb.append("%s **Stored Research:** %,d".format(textureEmote("/Game/UI/Foundation/Textures/Icons/Currency/T-Icon-ResearchPoint-128.T-Icon-ResearchPoint-128")?.asMention, researchPoints))
		embed.addField("Research", sb.toString(), true)
		embed.addField("Collection Book", "**Level:** %,d\n**Unslot Cost:** %s %,d".format(
			stats.collection_book.maxBookXpLevelAchieved,
			Utils.MTX_EMOJI,
			stats.unslot_mtx_spend
		), true)
		val df = DateFormat.getDateInstance()
		embed.addField("Dates", "**Creation Date:** %s\n**Last Updated:** %s".format(
			df.format(campaign.created),
			df.format(campaign.updated)
		), true)
		embed.addField("Miscellaneous", "**Mythic Schematics:** %,d\n**Revisions:** %,d\n**Zones Completed:** %,d".format(
			mythicSchematics.size,
			campaign.rvn,
			stats.gameplay_stats?.firstOrNull { it.statName == "zonescompleted" }?.statValue?.toIntOrNull() ?: 0
		), true)
		if (canReceiveMtxCurrency) {
			embed.setFooter("Founders Account", Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Items/T-Items-MTX.T-Items-MTX"))
		} else {
			embed.setFooter("Non-Founders Account", Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Items/T-Items-Currency-X-RayLlama.T-Items-Currency-X-RayLlama"))
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}