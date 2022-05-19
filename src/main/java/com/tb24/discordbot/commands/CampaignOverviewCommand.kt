package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.QueryPublicProfile
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.fn.model.mcpprofile.stats.CommonPublicProfileStats
import com.tb24.fn.util.Formatters
import me.fungames.jfortniteparse.fort.enums.EFortStatType.*
import net.dv8tion.jda.api.utils.TimeFormat
import java.util.*

class CampaignOverviewCommand : BrigadierCommand("stw", "Shows campaign statistics of an account.", arrayOf("profile")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Loading profile")

	private fun execute(source: CommandSourceStack, campaign: McpProfile): Int {
		if (!source.unattended) {
			source.ensureCompletedCampaignTutorial(campaign)
			if (source.api.currentLoggedIn.id == campaign.owner.id) {
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "common_public").await()
			} else {
				source.api.profileManager.dispatchPublicCommandRequest(campaign.owner, QueryPublicProfile(), "common_public").await()
			}
		}
		val stats = campaign.stats as CampaignProfileStats
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
		val foundersTiers = TreeSet<FoundersEdition>()
		var canReceiveMtxCurrency = false
		for (item in campaign.items.values) {
			when {
				item.templateId == "Token:collectionresource_nodegatetoken01" -> researchPoints += item.quantity
				item.templateId.contains("stormking_sr") -> mythicSchematics.add(item)
				item.templateId == "Quest:foundersquest_getrewards_0_1" -> foundersTiers.add(FoundersEdition.STANDARD)
				item.templateId == "Quest:foundersquest_getrewards_1_2" -> foundersTiers.add(FoundersEdition.DELUXE)
				item.templateId == "Quest:foundersquest_getrewards_2_3" -> foundersTiers.add(FoundersEdition.SUPER_DELUXE)
				item.templateId == "Quest:foundersquest_getrewards_3_4" -> foundersTiers.add(FoundersEdition.LIMITED)
				item.templateId == "Quest:foundersquest_getrewards_4_5" -> foundersTiers.add(FoundersEdition.ULTIMATE)
				item.templateId == "Token:receivemtxcurrency" -> canReceiveMtxCurrency = true
				item.templateId in quests -> questItems[item.templateId] = item
			}
		}
		val hb = source.session.getHomebase(campaign.owner.id)
		val fort = arrayOf(Fortitude, Offense, Resistance, Technology)
		val fortStr = fort.joinToString(" ") { "%s %,d".format(textureEmote(it.icon), hb.getStatBonus(it)) }
		val embed = source.createEmbed(campaign.owner)
			.setDescription("%s\n**Commander Level:** %,d\n**Days Logged in:** %,d\n**Homebase Name:** %s"
				.format(fortStr, stats.level + stats.rewards_claimed_post_max_level, stats.daily_rewards.totalDaysLoggedIn, homebaseName))
		embed.addField("Banner Challenges", quests.joinToString("\n") { questTemplateId ->
			val questItem = campaign.items.values.firstOrNull { it.templateId == questTemplateId }
				?: FortItemStack(questTemplateId, 1)
			val (completion, max) = getQuestCompletion(questItem)
			"%s **%s:**\n%,d/%,d (%s)".format(
				textureEmote(questItem.defData.LargePreviewImage.toString())?.asMention,
				questItem.displayName,
				completion, max,
				Formatters.percentZeroFraction.format(completion.toDouble() / max.toDouble())
			)
		}, true)
		embed.addField("Dates", "**First Played:**\n%s\n**Last Updated:**\n%s".format(
			TimeFormat.DATE_LONG.format(campaign.created.time),
			TimeFormat.DATE_LONG.format(campaign.updated.time)
		), true)
		val sb = StringBuilder()
		sb.append(arrayOf(arrayOf(Fortitude, Offense), arrayOf(Resistance, Technology)).joinToString("\n") { line ->
			line.joinToString(" ") { statType -> "%s %,d".format(textureEmote(statType.icon)?.asMention, stats.research_levels[statType]) }
		})
		sb.append("\n%s %,d".format(textureEmote("/Game/UI/Foundation/Textures/Icons/Currency/T-Icon-ResearchPoint-128.T-Icon-ResearchPoint-128")?.asMention, researchPoints))
		embed.addField("Research", sb.toString(), true)
		embed.addField("Collection Book", "**Level:** %,d\n**Spent for Unslotting:** %,d".format(
			stats.collection_book.maxBookXpLevelAchieved ?: 0,
			stats.unslot_mtx_spend
		), true)
		embed.addField("Inventory Size", "**Armory:** %,d\n**Backpack:** %,d\n**Storage:** %,d".format(
			hb.getAccountInventorySize(),
			hb.getWorldInventorySize(),
			hb.getStorageInventorySize()
		), true)
		embed.addField("Miscellaneous", "**Mythic Schematics:** %,d\n**Revisions:** %,d\n**Zones Completed:** %,d".format(
			mythicSchematics.size,
			campaign.rvn,
			stats.gameplay_stats.firstOrNull { it.statName == "zonescompleted" }?.statValue?.toIntOrNull() ?: 0
		), true)
		val foundersEdition = if (foundersTiers.isNotEmpty()) foundersTiers.last() else null
		if (foundersEdition != null || canReceiveMtxCurrency) {
			embed.setFooter(foundersEdition?.let { it.displayName + " Founders Account" } ?: "Founders Account (Unknown edition, account is broken!)", Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Boost/T-Icon-FoundersBadge-128.T-Icon-FoundersBadge-128"))
		} else {
			embed.setFooter("Non-Founders Account", Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Items/T-Items-Currency-X-RayLlama.T-Items-Currency-X-RayLlama"))
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	enum class FoundersEdition(val displayName: String) {
		STANDARD("Standard"),
		DELUXE("Deluxe"),
		SUPER_DELUXE("Super Deluxe"),
		LIMITED("Limited"),
		ULTIMATE("Ultimate")
	}
}