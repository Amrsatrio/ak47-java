package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.util.*
import com.tb24.fn.model.assetdata.FortActiveTheaterInfo
import com.tb24.fn.model.assetdata.FortActiveTheaterInfo.FortAvailableMissionAlertData
import com.tb24.fn.model.assetdata.FortActiveTheaterInfo.FortAvailableMissionData
import com.tb24.fn.model.assetdata.FortMissionGenerator
import com.tb24.fn.model.assetdata.FortTheaterInfo.FortTheaterMapData
import com.tb24.fn.model.assetdata.FortZoneTheme
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats.FortMissionAlertClaimData
import com.tb24.fn.util.format
import com.tb24.fn.util.getPathName
import com.tb24.uasset.StructJson
import com.tb24.uasset.loadCDO
import me.fungames.jfortniteparse.fort.objects.rows.GameDifficultyInfo
import net.dv8tion.jda.api.MessageBuilder

class MissionAlertsCommand : BrigadierCommand("alerts", "Shows today's mission alerts.", arrayOf("ma")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(literal("completed")
			.withPublicProfile(::execute, "Getting mission alerts info")
		)

	private fun execute(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		val canReceiveMtxCurrency = campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
		val stats = campaign.stats as CampaignProfileStats
		val completedAlerts = stats.mission_alert_redemption_record?.claimData
		val entries = mutableListOf<Pair<FortMissionAlertClaimData, Pair<String, String>>>()
		if (!completedAlerts.isNullOrEmpty()) {
			queryTheaters(source).iterateMissions { theater, mission, missionAlert ->
				if (missionAlert != null) {
					val claimData = completedAlerts.firstOrNull { it.missionAlertId == missionAlert.MissionAlertGuid }
					if (claimData != null) {
						entries.add(claimData to mission.render(theater, missionAlert, stats, canReceiveMtxCurrency))
					}
				}
				true
			}
		}
		if (entries.isEmpty()) {
			source.complete(null, source.createEmbed(campaign.owner).setColor(COLOR_ERROR)
				.setDescription("❌ No completed mission alerts found.")
				.build())
			return Command.SINGLE_SUCCESS
		}
		source.message.replyPaginated(entries.sortedByDescending { it.first.redemptionDateUtc }, 5, source.loadingMsg) { content, page, pageCount ->
			val entriesStart = page * 5 + 1
			val entriesEnd = entriesStart + content.size
			val embed = source.createEmbed(campaign.owner)
				.setTitle("Completed mission alerts")
				.setDescription("Showing %,d to %,d of %,d entries".format(entriesStart, entriesEnd - 1, entries.size))
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			for (entry in content) {
				embed.addField(entry.second.first, entry.second.second + '\n' + entry.first.redemptionDateUtc.relativeFromNow(), false)
			}
			MessageBuilder(embed).build()
		}
		return Command.SINGLE_SUCCESS
	}
}

class MtxAlertsCommand : BrigadierCommand("vbucksalerts", "Shows today's V-Bucks mission alerts.", arrayOf("va", "mtxalerts")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting mission alerts info")

	private fun execute(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		val canReceiveMtxCurrency = campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
		val stats = campaign.stats as CampaignProfileStats
		var totalMtx = 0
		val embed = source.createEmbed(campaign.owner)
		queryTheaters(source).iterateMissions { theater, mission, missionAlert ->
			val mtxLoot = missionAlert?.MissionAlertRewards?.items?.firstOrNull { it.itemType == "AccountResource:currency_mtxswap" }
				?: return@iterateMissions true
			totalMtx += mtxLoot.quantity
			val (title, value) = mission.render(theater, missionAlert, stats, canReceiveMtxCurrency)
			embed.addField(title, value, false)
			true
		}
		if (embed.fields.isEmpty()) {
			embed.setDescription("There are no V-Bucks mission alerts today :(")
		} else {
			embed.setFooter("%,d V-Bucks today".format(totalMtx))
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}

private fun queryTheaters(source: CommandSourceStack) =
	source.api.fortniteService.queryTheaterList("en").exec().body()!!.charStream()
		.use { StructJson.GSON.fromJson(it, FortActiveTheaterInfo::class.java) }

typealias MissionVisitorFunction = (theater: FortTheaterMapData, mission: FortAvailableMissionData, missionAlert: FortAvailableMissionAlertData?) -> Boolean

inline fun FortActiveTheaterInfo.iterateMissions(func: MissionVisitorFunction) {
	for (theater in Theaters) {
		val missions = getAvailableMissions(theater)
		val missionAlerts = getMissionAlerts(theater)
		for (mission in missions.AvailableMissions) {
			val missionAlert = missionAlerts.getMissionAlert(mission)
			if (!func(theater, mission, missionAlert)) {
				return
			}
		}
	}
}

private fun FortAvailableMissionData.render(theater: FortTheaterMapData, missionAlert: FortAvailableMissionAlertData, attrs: CampaignProfileStats, canReceiveMtxCurrency: Boolean): Pair<String, String> {
	val missionGenerator = loadCDO(MissionGenerator.toString(), FortMissionGenerator::class.java)
	val difficulty = MissionDifficultyInfo.getRowMapped<GameDifficultyInfo>()
	val tile = theater.Tiles[TileIndex]
	val zoneTheme = loadCDO(tile.ZoneTheme.toString(), FortZoneTheme::class.java)
	val hasCompletedMissionAlert = attrs.mission_alert_redemption_record?.claimData?.any { it.missionAlertId == missionAlert.MissionAlertGuid } == true

	val strike = if (hasCompletedMissionAlert) "~~" else ""
	val title = "%s[%,d] %s %s%s%s".format(
		strike,
		difficulty.RecommendedRating,
		textureEmote(missionGenerator?.MissionIcon?.ResourceObject?.getPathName())?.asMention,
		missionGenerator?.MissionName?.format() ?: MissionGenerator.toString().substringAfterLast('.'),
		strike,
		if (hasCompletedMissionAlert) " ✅" else ""
	)
	val sb = StringBuilder()
	sb.append(zoneTheme?.ZoneName?.format() ?: tile.ZoneTheme.toString().substringAfterLast('.'))
	sb.append(" - ${theater.DisplayName.format()}")
	//if (missionAlert != null) {
	//sb.append('\n').append("__Mission alert rewards__")
	missionAlert.MissionAlertRewards.items.forEach {
		sb.append('\n').append(it.asItemStack().apply { setConditionForConditionalItem(canReceiveMtxCurrency) }.renderWithIcon())
	}
	//}
	return title to sb.toString()
}