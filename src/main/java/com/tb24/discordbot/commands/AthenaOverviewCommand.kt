package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
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
				?: throw SimpleCommandExceptionType(LiteralMessage("Season data not found.")).create()
			val xpToNextLevel = getXpToNextLevel(seasonData, attrs.level)
			val nextLevelReward = getNextLevelReward(seasonData, attrs.level, attrs.book_purchased)
			val inventory = source.api.fortniteService.inventorySnapshot(source.api.currentLoggedIn.id).exec().body()!!
			val embed = source.createEmbed()
				.setTitle("Season " + attrs.season_num)
				.addField("Level %,d".format(attrs.level), "`%s`\n%,d / %,d".format(Utils.progress(attrs.xp, xpToNextLevel, 32), attrs.xp, xpToNextLevel), false)
			if (nextLevelReward != null) {
				val rewardItem = nextLevelReward.asItemStack()
				embed.addField("Rewards for level %,d".format(attrs.level + 1), rewardItem.render(), false)
				embed.setThumbnail(Utils.benBotExportAsset(rewardItem.getPreviewImagePath(true).toString()))
			}
			var restedXpText = "`%s`\n%,d / %,d\nMultiplier: %,.2f\u00d7".format(Utils.progress(attrs.rested_xp, seasonData.RestedXpMaxAccrue, 32), attrs.rested_xp, seasonData.RestedXpMaxAccrue, attrs.rested_xp_mult)
			if (attrs.rested_xp >= seasonData.RestedXpMaxAccrue) {
				restedXpText += "\n⚠ " + "Your supercharged XP is at maximum. You won't be granted more supercharged XP if you don't complete your daily challenges until you deplete it."
			}
			embed.addField("Supercharged XP", restedXpText, false)
			embed.addField("Account Level", Formatters.num.format(attrs.accountLevel), false)
			embed.addField("Bars", Formatters.num.format(inventory.stash["globalcash"] ?: 0), false)
			source.complete(null, embed.build())
			Command.SINGLE_SUCCESS
		}

	private fun getXpToNextLevel(defData: AthenaSeasonItemDefinition?, level: Int): Int {
		if (defData == null) {
			return 0
		}
		if (level >= defData.NumBookLevels) {
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

	private fun getNextLevelReward(defData: AthenaSeasonItemDefinition, level: Int, vip: Boolean): AthenaRewardItemReference? {
		val lookupLevel = level + 1
		val determinedLevelsArray = (if (vip) defData.BookXpSchedulePaid else defData.BookXpScheduleFree).Levels
		determinedLevelsArray.getOrNull(lookupLevel)?.Rewards?.firstOrNull()?.let { return it }
		if (!vip) {
			return null // free pass, already queried from BookXpScheduleFree
		} // else battle pass, previously it was queried from BookXpSchedulePaid, now query from BookXpScheduleFree
		return defData.BookXpScheduleFree.Levels.getOrNull(lookupLevel)?.Rewards?.firstOrNull()
	}
}