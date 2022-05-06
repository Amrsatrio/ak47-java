package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.images.generateMissionAlertsImage
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
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
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.NewsChannel
import net.dv8tion.jda.api.interactions.commands.OptionType

class MissionAlertsCommand : BrigadierCommand("alerts", "Shows today's mission alerts.", arrayOf("ma")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(literal("image")
			.executes { image(it.source) }
		)
		.then(literal("completed")
			.withPublicProfile(::execute, "Getting mission alerts info")
		)

	private fun image(source: CommandSourceStack): Int {
		source.conditionalUseInternalSession()
		source.loading("Getting mission alerts")
		val theaters = queryTheaters(source)
		source.loading("Generating and uploading image")
		val image = generateMissionAlertsImage(theaters)
		source.complete(AttachmentUpload(image.toPngArray(), "mission_alerts.png"))
		return Command.SINGLE_SUCCESS
	}

	private fun execute(source: CommandSourceStack, campaign: McpProfile): Int {
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
		source.replyPaginated(entries.sortedByDescending { it.first.redemptionDateUtc }, 5) { content, page, pageCount ->
			val entriesStart = page * 5 + 1
			val entriesEnd = entriesStart + content.size
			val embed = source.createEmbed(campaign.owner)
				.setTitle("Completed mission alerts")
				.setDescription("Showing %,d to %,d of %,d entries".format(entriesStart, entriesEnd - 1, entries.size))
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			for (entry in content) {
				embed.addField(entry.second.first, entry.second.second + '\n' + entry.first.redemptionDateUtc.relativeFromNow(), false)
			}
			MessageBuilder(embed)
		}
		return Command.SINGLE_SUCCESS
	}
}

class MtxAlertsCommand : BrigadierCommand("vbucksalerts", "Shows today's V-Bucks mission alerts.", arrayOf("va", "mtxalerts")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::executeMtxAlerts, "Getting mission alerts info")
		.then(literal("bulk")
			.executes { executeBulk(it.source) }
			.then(argument("users", UserArgument.users(100))
				.executes { executeBulk(it.source, lazy { UserArgument.getUsers(it, "users").values }) }
			)
		)

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("view", description)
			.withPublicProfile(::executeMtxAlerts, "Getting mission alerts info")
		)
		.then(subcommand("bulk", "Shows the completion of V-Bucks mission alerts of multiple users.")
			.option(OptionType.STRING, "users", "Users to display or leave blank to display your saved accounts", argument = UserArgument.users(100))
			.executes { source ->
				val usersResult = source.getArgument<UserArgument.Result>("users")
				executeBulk(source, usersResult?.let { lazy { it.getUsers(source).values } })
			}
		)

	private fun executeBulk(source: CommandSourceStack, usersLazy: Lazy<Collection<GameProfile>>? = null): Int {
		source.conditionalUseInternalSession()
		source.loading("Getting mission alerts info")
		val mtxAlerts = mutableMapOf<String, Pair<Int, Int>>()
		var totalMtx = 0
		var completedCount = 0
		var totalEarnedMtx = 0
		queryTheaters(source).iterateMissions { _, mission, missionAlert ->
			val mtxLoot = missionAlert?.MissionAlertRewards?.items?.firstOrNull { it.itemType == "AccountResource:currency_mtxswap" }
				?: return@iterateMissions true
			totalMtx += mtxLoot.quantity
			mtxAlerts[missionAlert.MissionAlertGuid] = mission.MissionDifficultyInfo.getRowMapped<GameDifficultyInfo>()!!.RecommendedRating to mtxLoot.quantity
			true
		}
		if (mtxAlerts.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("There are no V-Bucks mission alerts today :(")).create()
		}
		val entries = stwBulk(source, usersLazy) { campaign ->
			//val completedTutorial = (campaign.items.values.firstOrNull { it.templateId == "Quest:homebaseonboarding" }?.attributes?.get("completion_hbonboarding_completezone")?.asInt ?: 0) > 0
			val canReceiveMtxCurrency = campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
			if (/*!completedTutorial ||*/ !canReceiveMtxCurrency) {
				return@stwBulk null
			}
			val attrs = campaign.stats as CampaignProfileStats
			campaign.owner.displayName to mtxAlerts.entries.joinToString(" ") { (alertGuid, ratingAndNumMtx) ->
				val hasCompletedMissionAlert = attrs.mission_alert_redemption_record?.claimData?.any { it.missionAlertId == alertGuid } == true
				"%,d: %s".format(ratingAndNumMtx.first, if (hasCompletedMissionAlert) {
					completedCount++
					totalEarnedMtx += ratingAndNumMtx.second
					"✅"
				} else "❌")
			}
		}
		if (entries.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("All users we're trying to display don't have STW founders.")).create()
		}
		val embed = EmbedBuilder().setColor(COLOR_INFO)
			.setTitle("%s %,d/%,d @ %,d".format(Utils.MTX_EMOJI, totalEarnedMtx, entries.size * totalMtx, totalMtx))
		val inline = entries.size >= 6
		for (entry in entries) {
			if (embed.fields.size == 25) {
				source.complete(null, embed.build())
				embed.setTitle(null).clearFields()
			}
			embed.addField(entry.first, entry.second, inline)
		}
		val numAlerts = entries.size * mtxAlerts.size
		val footerInfo = StringBuilder("%,d/%,d alerts completed".format(completedCount, numAlerts))
		val remaining = numAlerts - completedCount
		if (remaining > 0) {
			footerInfo.append(", %,d remaining".format(remaining))
		}
		source.complete(null, embed.setFooter(footerInfo.toString()).build())
		return Command.SINGLE_SUCCESS
	}
}

fun executeMtxAlerts(source: CommandSourceStack, campaign: McpProfile? = null): Int {
	val canReceiveMtxCurrency = if (campaign != null) {
		source.ensureCompletedCampaignTutorial(campaign)
		campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
	} else true
	val stats = campaign?.stats as? CampaignProfileStats
	var totalMtx = 0
	val embed = if (campaign != null) source.createEmbed(campaign.owner) else EmbedBuilder().setColor(0x0099FF)
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
		if (campaign != null) {
			embed.setFooter("%,d V-Bucks today".format(totalMtx))
		} else {
			embed.setTitle("%s %,d".format(Utils.MTX_EMOJI, totalMtx))
		}
	}
	val role = if (campaign == null) {
		source.guild!!.getRoleById(BotConfig.get().mtxAlertsRoleId)
			?: source.guild.getRolesByName("V-Bucks Alerts Ping", true).firstOrNull()
	} else null
	val message = source.complete(if (role != null && embed.fields.isNotEmpty()) role.asMention else null, embed.build())
	if (source.channel is NewsChannel) {
		message.crosspost().queue()
	}
	return Command.SINGLE_SUCCESS
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

private fun FortAvailableMissionData.render(theater: FortTheaterMapData, missionAlert: FortAvailableMissionAlertData, attrs: CampaignProfileStats?, canReceiveMtxCurrency: Boolean): Pair<String, String> {
	val missionGenerator = loadCDO(MissionGenerator.toString(), FortMissionGenerator::class.java)
	val difficulty = MissionDifficultyInfo.getRowMapped<GameDifficultyInfo>()!!
	val tile = theater.Tiles[TileIndex]
	val zoneTheme = loadCDO(tile.ZoneTheme.toString(), FortZoneTheme::class.java)
	val hasCompletedMissionAlert = attrs?.mission_alert_redemption_record?.claimData?.any { it.missionAlertId == missionAlert.MissionAlertGuid } == true

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