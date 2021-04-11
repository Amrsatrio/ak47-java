package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.attributes.AthenaProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.asItemStack
import com.tb24.fn.util.getPreviewImagePath
import me.fungames.jfortniteparse.fort.exports.AthenaSeasonItemDefinition
import me.fungames.jfortniteparse.fort.objects.AthenaRewardItemReference
import me.fungames.jfortniteparse.fort.objects.rows.AthenaExtendedXPCurveEntry
import me.fungames.jfortniteparse.fort.objects.rows.AthenaSeasonalXPCurveEntry
import me.fungames.jfortniteparse.ue4.objects.uobject.FName

class AthenaOverviewCommand : BrigadierCommand("br", "Shows your BR level of current season.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Getting BR data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val attrs = source.api.profileManager.getProfileData("athena").stats.attributes as AthenaProfileAttributes
			val seasonData = FortItemStack("AthenaSeason:athenaseason${attrs.season_num}", 1).defData as? AthenaSeasonItemDefinition
			val xpToNextLevel = getXpToNextLevel(seasonData, attrs.level)
			val nextLevelReward = getNextLevelReward(seasonData, attrs.level, attrs.book_purchased)
			val inventory = source.api.fortniteService.inventorySnapshot(source.api.currentLoggedIn.id).exec().body()!!
			val embed = source.createEmbed()
				.setTitle("Season " + attrs.season_num)
				.addField("%s Level %,d".format(if (attrs.book_purchased) "Battle Pass" else "Free Pass", attrs.level), "`%s`\n%,d / %,d".format(Utils.progress(attrs.xp, xpToNextLevel, 32), attrs.xp, xpToNextLevel), false)
			if (nextLevelReward != null) {
				val rewardItem = nextLevelReward.asItemStack()
				embed.addField("Rewards for level %,d".format(attrs.level + 1), rewardItem.render(), false)
				embed.setThumbnail(Utils.benBotExportAsset(rewardItem.getPreviewImagePath(true)?.toString()))
			}
			if (attrs.xp_overflow > 0) {
				embed.addField("XP Overflow", Formatters.num.format(attrs.xp_overflow), false)
			}
			if (attrs.rested_xp > 0) {
				val restedXpMaxAccrue = seasonData?.RestedXpMaxAccrue ?: 0
				var restedXpText = "`%s`\n%,d / %,d\nMultiplier: %,.2f\u00d7 \u00b7 Exchange: %,.2f \u00b7 Overflow: %,d".format(
					Utils.progress(attrs.rested_xp, restedXpMaxAccrue, 32),
					attrs.rested_xp, restedXpMaxAccrue,
					attrs.rested_xp_mult, attrs.rested_xp_exchange, attrs.rested_xp_overflow)
				if (restedXpMaxAccrue > 0 && attrs.rested_xp >= restedXpMaxAccrue) {
					restedXpText += "\n⚠ " + "Your supercharged XP is at maximum. You won't be granted more supercharged XP if you don't complete your daily challenges until you deplete it."
				}
				embed.addField("Supercharged XP", restedXpText, false)
			}
			embed.addField("Account Level", Formatters.num.format(attrs.accountLevel), false)
			embed.addField("Bars", "%s %,d".format(barsEmote?.asMention, inventory.stash["globalcash"] ?: 0), false)
			attrs.last_match_end_datetime?.apply {
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
				val attrs = source.api.profileManager.getProfileData("athena").stats.attributes as AthenaProfileAttributes
				val embed = source.createEmbed()
				embed.addField("Lifetime wins", Formatters.num.format(attrs.lifetime_wins), true)
				embed.addField("2FA reward claimed", if (attrs.mfa_reward_claimed) "✅" else "❌", true)
				embed.addFieldSeparate("Past seasons", attrs.past_seasons.toList(), 0) {
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

	private fun getNextLevelReward(defData: AthenaSeasonItemDefinition?, level: Int, vip: Boolean): AthenaRewardItemReference? {
		if (defData == null) return null
		val lookupLevel = level + 1
		val determinedLevelsArray = (if (vip) defData.BookXpSchedulePaid else defData.BookXpScheduleFree)?.Levels ?: return null
		determinedLevelsArray.getOrNull(lookupLevel)?.Rewards?.firstOrNull()?.let { return it }
		if (!vip) {
			return null // free pass, already queried from BookXpScheduleFree
		} // else battle pass, previously it was queried from BookXpSchedulePaid, now query from BookXpScheduleFree
		return defData.BookXpScheduleFree.Levels.getOrNull(lookupLevel)?.Rewards?.firstOrNull()
	}
}