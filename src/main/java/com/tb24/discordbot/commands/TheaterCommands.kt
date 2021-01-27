package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.renderWithIcon
import com.tb24.fn.model.assetdata.FortActiveTheaterInfo
import com.tb24.fn.model.assetdata.FortMissionGenerator
import com.tb24.fn.model.assetdata.FortZoneTheme
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.attributes.CampaignProfileAttributes
import com.tb24.fn.util.format
import com.tb24.uasset.StructJson
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.objects.rows.GameDifficultyInfo
import me.fungames.jfortniteparse.ue4.assets.exports.UObject

class MtxAlertsCommand : BrigadierCommand("vbucksalerts", "Shows today's V-Bucks alerts.", arrayOf("va", "mtxalerts")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting mission alerts info")

	fun execute(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		val canReceiveMtxCurrency = campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
		val attrs = campaign.stats.attributes as CampaignProfileAttributes
		val response = source.api.fortniteService.queryTheaterList("en").exec().body()!!.charStream()
			.use { StructJson.GSON.fromJson(it, FortActiveTheaterInfo::class.java) }
		val lines = mutableListOf<String>()
		for (theater in response.Theaters) {
			val missions = response.getAvailableMissions(theater)
			val missionAlerts = response.getMissionAlerts(theater)
			for (mission in missions.AvailableMissions) {
				val missionAlert = missionAlerts.getMissionAlert(mission)
				val tile = theater.Tiles[mission.TileIndex]
				missionAlert?.MissionAlertRewards?.items?.firstOrNull { it.itemType == "AccountResource:currency_mtxswap" }
					?: continue
				val difficulty = mission.MissionDifficultyInfo.getRowMapped<GameDifficultyInfo>()
				val missionGenerator = loadCDO<FortMissionGenerator>(mission.MissionGenerator.toString())
				val zoneTheme = loadCDO<FortZoneTheme>(tile.ZoneTheme.toString())
				val hasCompletedMissionAlert = attrs.mission_alert_redemption_record.claimData.any { it.missionAlertId == missionAlert.MissionAlertGuid }

				val sb = StringBuilder()
				val strike = if (hasCompletedMissionAlert) "~~" else ""
				sb.append("%s**⚡%,d".format(strike, difficulty.RecommendedRating))
				sb.append(" - ").append(missionGenerator?.MissionName?.format() ?: mission.MissionGenerator.toString().substringAfterLast('.'))
				sb.append("**$strike")
				if (hasCompletedMissionAlert) {
					sb.append(" ✅")
				}
				sb.append('\n')
				sb.append(zoneTheme?.ZoneName?.format() ?: tile.ZoneTheme.toString().substringAfterLast('.'))
				sb.append(" - ${theater.DisplayName.format()}")
				//if (missionAlert != null) {
				//sb.append('\n').append("__Mission alert rewards__")
				missionAlert.MissionAlertRewards.items.forEach {
					sb.append("\n\u2022 ").append(it.asItemStack().apply { setConditionForConditionalItem(canReceiveMtxCurrency) }.renderWithIcon())
				}
				//}
				lines.add(sb.toString())
			}
		}
		source.complete(null, source.createEmbed(campaign.owner)
			//.setTitle("Today's V-Bucks alerts")
			.setDescription(if (lines.isNotEmpty()) lines.joinToString("\n\n") else "There are no V-Bucks mission alerts today :(")
			.build())
		return Command.SINGLE_SUCCESS
	}

	private inline fun <reified T : UObject> loadCDO(path: String) =
		loadObject<T>(StringBuilder(path).insert(path.lastIndexOf('.') + 1, "Default__").toString())
}