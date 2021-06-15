package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.AthenaSeasonItemData_Level
import com.tb24.fn.model.assetdata.AthenaSeasonItemDefinition
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.asItemStack
import com.tb24.fn.util.getPreviewImagePath
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition
import me.fungames.jfortniteparse.fort.objects.rows.AthenaExtendedXPCurveEntry
import me.fungames.jfortniteparse.fort.objects.rows.AthenaSeasonalXPCurveEntry
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import java.text.DateFormat

val battleStarEmote = textureEmote("/Game/Athena/UI/Frontend/Art/T_UI_BP_BattleStar_L.T_UI_BP_BattleStar_L")
val alienCurrencyEmote = textureEmote("/Game/Athena/UI/Frontend/Art/T_UI_BP_AlienCurrency_L.T_UI_BP_AlienCurrency_L")

class AthenaOverviewCommand : BrigadierCommand("br", "Shows your BR level of current season.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting BR data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val stats = source.api.profileManager.getProfileData("athena").stats as AthenaProfileStats
			val seasonData = FortItemStack("AthenaSeason:athenaseason${stats.season_num}", 1).defData as? AthenaSeasonItemDefinition
			val xpToNextLevel = getXpToNextLevel(seasonData, stats.level)
			val nextLevelReward = getNextLevelReward(seasonData, stats.level, stats.book_purchased)
			val inventory = source.api.fortniteService.inventorySnapshot(source.api.currentLoggedIn.id).exec().body()!!
			val embed = source.createEmbed()
				.setTitle("Season " + stats.season_num)
				.addField("%s Level %,d".format(if (stats.book_purchased) "Battle Pass" else "Free Pass", stats.level), "`%s`\n%,d / %,d".format(Utils.progress(stats.xp, xpToNextLevel, 32), stats.xp, xpToNextLevel), false)
			if (nextLevelReward != null) {
				embed.addField("Rewards for level %,d".format(stats.level + 1), nextLevelReward.renderWithIcon(), false)
				embed.setThumbnail(Utils.benBotExportAsset(nextLevelReward.getPreviewImagePath(true)?.toString()))
			}
			if (stats.xp_overflow > 0) {
				embed.addField("XP Overflow", Formatters.num.format(stats.xp_overflow), false)
			}
			if (stats.rested_xp > 0) {
				val restedXpMaxAccrue = seasonData?.RestedXpMaxAccrue ?: 0
				var restedXpText = "`%s`\n%,d / %,d\nMultiplier: %,.2f\u00d7 \u00b7 Exchange: %,.2f \u00b7 Overflow: %,d".format(
					Utils.progress(stats.rested_xp, restedXpMaxAccrue, 32),
					stats.rested_xp, restedXpMaxAccrue,
					stats.rested_xp_mult, stats.rested_xp_exchange, stats.rested_xp_overflow)
				if (restedXpMaxAccrue > 0 && stats.rested_xp >= restedXpMaxAccrue) {
					restedXpText += "\n⚠ " + "Your supercharged XP is at maximum. You won't be granted more supercharged XP if you don't complete your daily challenges until you deplete it."
				}
				embed.addField("Supercharged XP", restedXpText, false)
			}
			embed.addField("Account Level", Formatters.num.format(stats.accountLevel), false)
			val currentBattleStars = stats.battlestars ?: 0
			val totalBattleStars = stats.battlestars_season_total ?: 0
			val spentBattleStars = totalBattleStars - currentBattleStars
			val currentAlienStylePoints = stats.alien_style_points ?: 0
			val spentAlienStylePoints = stats.purchasedBpOffers.values.sumOf { if (it.currencyType == "alien_style_points") it.totalCurrencyPaid else 0 }
			val totalAlienStylePoints = currentAlienStylePoints + spentAlienStylePoints
			embed.addField("Season Resources", "%s %s **%,d** (%,d spent, %,d total)\n%s %s **%,d** (%,d spent, %,d total)\n%s %s **%,d**".format(
				"Battle Stars", battleStarEmote?.asMention, currentBattleStars, spentBattleStars, totalBattleStars,
				"Alien Artifacts", alienCurrencyEmote?.asMention, currentAlienStylePoints, spentAlienStylePoints, totalAlienStylePoints,
				"Bars", barsEmote?.asMention, inventory.stash["globalcash"] ?: 0
			), false)
			stats.last_match_end_datetime?.apply {
				embed.setFooter("Last match end").setTimestamp(toInstant())
			}
			source.complete(null, embed.build())
			Command.SINGLE_SUCCESS
		}
		.then(literal("info")
			.executes { c ->
				val source = c.source
				source.ensureSession()
				source.loading("Getting BR data")
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
				val athena = source.api.profileManager.getProfileData("athena")
				val stats = athena.stats as AthenaProfileStats
				val embed = source.createEmbed()
				val df = DateFormat.getDateInstance()
				embed.addField("Dates", "**Creation Date:** %s\n**Last Updated:** %s".format(
					df.format(athena.created),
					df.format(athena.updated)
				), true)
				embed.addField("Wins", "**Season:** %,d\n**Lifetime:** %,d".format(stats.season?.numWins, stats.lifetime_wins), true)
				embed.addField("2FA reward claimed", if (stats.mfa_reward_claimed) "✅" else "❌", true)
				embed.addFieldSeparate("Past seasons", stats.past_seasons.toList(), 0) {
					if (it.seasonNumber >= 11) {
						"Season %,d: %s level %,d, %,d wins".format(it.seasonNumber, if (it.purchasedVIP) "Battle Pass" else "Free Pass", it.seasonLevel, it.numWins)
					} else {
						"Season %,d: Level %,d, %s tier %,d, %,d wins".format(it.seasonNumber, it.seasonLevel, if (it.purchasedVIP) "Battle Pass" else "Free Pass", it.bookLevel, it.numWins)
					}
				}
				source.complete(null, embed.build())
				Command.SINGLE_SUCCESS
			}
		)

	private fun getXpToNextLevel(defData: AthenaSeasonItemDefinition?, level: Int): Int {
		if (defData == null) {
			return 0
		}
		if (level >= defData.NumBookLevels ?: 100) {
			defData.SeasonXpOnlyExtendedCurve?.value
				?.findRowMapped<AthenaExtendedXPCurveEntry>(FName.dummy(level.toString()))
				?.let { return it.XpPerLevel }
		} else {
			defData.SeasonXpCurve?.value
				?.findRowMapped<AthenaSeasonalXPCurveEntry>(FName.dummy(level.toString()))
				?.let { return it.XpToNextLevel }
		}
		return 0
	}

	private fun getNextLevelReward(seasonDef: AthenaSeasonItemDefinition?, level: Int, vip: Boolean): FortItemStack? {
		if (seasonDef == null) return null
		val lookupLevel = level + 1
		if (seasonDef.bGenerateBookRewards == true) {
			if (lookupLevel >= seasonDef.NumBookLevels ?: 100) {
				seasonDef.SeasonXpOnlyExtendedCurve?.value
					?.findRowMapped<AthenaExtendedXPCurveEntry>(FName.dummy(lookupLevel.toString()))
					?.let { return FortItemStack(it.RewardItemAssetPerLevel.load<FortItemDefinition>(), it.RewardItemCountPerLevel) }
			} else {
				seasonDef.SeasonXpCurve?.value
					?.findRowMapped<AthenaSeasonalXPCurveEntry>(FName.dummy(lookupLevel.toString()))
					?.let { return FortItemStack(it.RewardItemAsset.load<FortItemDefinition>(), it.RewardItemCount) }
			}
			return null
		}
		var paid = seasonDef.BookXpSchedulePaid
		var free = seasonDef.BookXpScheduleFree
		val levelData = seasonDef.getAdditionalDataOfType<AthenaSeasonItemData_Level>()
		if (levelData != null) {
			paid = levelData.BattlePassXpSchedulePaid
			free = levelData.BattlePassXpScheduleFree
		}
		val determinedLevelsArray = (if (vip) paid else free)?.Levels ?: return null
		determinedLevelsArray.getOrNull(lookupLevel)?.Rewards?.firstOrNull()?.let { return it.asItemStack() }
		if (!vip) {
			return null // free pass, already queried from BookXpScheduleFree
		} // else battle pass, previously it was queried from BookXpSchedulePaid, now query from BookXpScheduleFree
		return seasonDef.BookXpScheduleFree.Levels.getOrNull(lookupLevel)?.Rewards?.firstOrNull()?.asItemStack()
	}
}