package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.INTRO_NAME
import com.tb24.discordbot.SEASON_NUM
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.*
import com.tb24.discordbot.util.Utils
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.assetdata.*
import com.tb24.fn.model.assetdata.AthenaSeasonItemDefinition.SeasonCurrencyMcpData
import com.tb24.fn.model.assetdata.rows.AthenaBattlePassOfferPriceRow
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.util.*
import com.tb24.fn.util.Utils.sumKV
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortPersistentResourceItemDefinition
import me.fungames.jfortniteparse.fort.objects.rows.AthenaExtendedXPCurveEntry
import me.fungames.jfortniteparse.fort.objects.rows.AthenaSeasonalXPCurveEntry
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.utils.TimeFormat
import java.util.concurrent.CompletableFuture

val battleStarEmote by lazy { textureEmote("/Game/Athena/UI/Frontend/Art/T_UI_BP_BattleStar_L.T_UI_BP_BattleStar_L") }
val battlePassEmote by lazy { textureEmote("/Game/UI/Foundation/Textures/Icons/Items/T-FNBR-BattlePass-L.T-FNBR-BattlePass-L") }
val freePassEmote by lazy { textureEmote("/Game/UI/Foundation/Textures/Icons/Items/T-FNBR-BattlePass-Default-L.T-FNBR-BattlePass-Default-L") }
val xpEmote by lazy { textureEmote("/Game/UI/Foundation/Textures/Icons/Items/T_UI_FNBR_XPeverywhere_L.T_UI_FNBR_XPeverywhere_L") }
val victoryCrownEmote by lazy { textureEmote("/VictoryCrownsGameplay/Icons/T-T-Icon-BR-VictoryCrownItem-L.T-T-Icon-BR-VictoryCrownItem-L") }

val seasonData by lazy { loadObject<AthenaSeasonItemDefinition>(AssetManager.INSTANCE.assetRegistry.lookup("AthenaSeason", "AthenaSeason$SEASON_NUM")?.objectPath) }

class AthenaOverviewCommand : BrigadierCommand("br", "Shows an overview of your Battle Royale data, such as current level and supercharged XP.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { summary(it.source) }
		.then(literal("info")
			.executes { info(it.source) }
		)
		.then(literal("bulk").executes { bulk(it.source, null) }.then(argument("users", UserArgument.users(100)).executes { bulk(it.source, UserArgument.getUsers(it, "users", loadingText = null)) }))

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("summary", description).executes(::summary))
		.then(subcommand("info", "Shows more info about your Battle Royale career.").executes(::info))

	private fun summary(source: CommandSourceStack): Int {
		source.ensureSession()
		if (!source.unattended) {
			source.loading("Getting BR data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		}
		val athena = source.api.profileManager.getProfileData("athena")
		val stats = athena.stats as AthenaProfileStats
		val seasonData = if (stats.season_num == SEASON_NUM) seasonData else null
		val xpToNextLevel = getXpToNextLevel(seasonData, stats.level)
		val nextLevelReward = getNextLevelReward(seasonData, stats.level, stats.book_purchased)
		val inventory = source.api.fortniteService.inventorySnapshot(source.api.currentLoggedIn.id).exec().body()!!
		val embed = source.createEmbed()
			.setTitle(getFriendlySeasonText(stats.season_num))
			.addField("%s Level %,d".format((if (stats.book_purchased) battlePassEmote else freePassEmote)?.asMention, stats.level), "`%s`\n%,d / %,d".format(Utils.progress(stats.xp, xpToNextLevel, 32), stats.xp, xpToNextLevel), false)
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
		val seasonResources = seasonCurrencyData.mapTo(mutableListOf()) {
			"%s %s **%,d**".format(it.def.DisplayName.format(), textureEmote(it.def.LargePreviewImage.toString())?.asMention, it.getBalance(stats))
		}
		seasonResources.add("%s %s **%,d**".format("Bars", barsEmote?.asMention, inventory.stash["globalcash"] ?: 0))
		embed.addField("Season Resources", seasonResources.joinToString("\n"), false)
		val victoryCrown = athena.items.values.firstOrNull { it.templateId == "VictoryCrown:defaultvictorycrown" }
		if (victoryCrown != null) {
			val victoryCrownAccountData = victoryCrown.attributes.getAsJsonObject("victory_crown_account_data")
			val hasVictoryCrown = victoryCrownAccountData?.getBoolean("has_victory_crown") ?: false
			val totalRoyalRoyalesAchievedCount = victoryCrownAccountData?.getInt("total_royal_royales_achieved_count") ?: 0
			embed.addField("Victory Crown", "Owned %s\nCrowned Wins %s **%,d**".format(if (hasVictoryCrown) "✅" else "❌", victoryCrownEmote?.asMention, totalRoyalRoyalesAchievedCount), false)
		}
		stats.last_match_end_datetime?.apply {
			embed.setFooter("Last match end").setTimestamp(toInstant())
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun info(source: CommandSourceStack): Int {
		source.ensureSession()
		val profileManager = source.api.profileManager
		if (!source.unattended) {
			source.loading("Getting BR data")
			CompletableFuture.allOf(
				profileManager.dispatchClientCommandRequest(QueryProfile(), "common_core"),
				profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
			).await()
		}
		val athena = profileManager.getProfileData("athena")
		val stats = athena.stats as AthenaProfileStats
		val embed = source.createEmbed()
		embed.addField("Dates", "**First Played:** %s\n**Last Updated:** %s".format(
			TimeFormat.DATE_LONG.format(athena.created.time),
			TimeFormat.DATE_LONG.format(athena.updated.time)
		), true)
		embed.addField("Wins", "**Season:** %,d\n**Lifetime:** %,d".format(stats.season?.numWins ?: 0, stats.lifetime_wins), true)
		embed.addField("Account Level", Formatters.num.format(stats.accountLevel), true)
		embed.addField("2FA reward claimed", if (stats.mfa_reward_claimed) "✅" else "❌", true)
		val currentBattleStars = stats.battlestars ?: 0
		val totalBattleStars = stats.battlestars_season_total ?: 0
		val spentBattleStars = totalBattleStars - currentBattleStars
		val currentStylePoints = stats.style_points ?: 0
		val totalStylePoints = stats.style_points_season_total ?: 0
		val spentStylePoints = totalStylePoints - currentStylePoints
		embed.addField("%s Battle Stars".format(battleStarEmote?.asMention), "%,d spent, %,d total".format(spentBattleStars, totalBattleStars), true)
		embed.addField("Style Points", "%,d spent, %,d total".format(spentStylePoints, totalStylePoints), true)
		INTRO_NAME?.let {
			embed.addField("Intro Played", if ((profileManager.getProfileData("common_core").stats as CommonCoreProfileStats).forced_intro_played == it) "✅" else "❌", true)
		}
		embed.addFieldSeparate("Past seasons", stats.past_seasons.toList(), 0) {
			if (it.seasonNumber >= 11) {
				"%s `%2d` Level %,d, %,d wins%s".format((if (it.purchasedVIP) battlePassEmote else freePassEmote)?.asMention, it.seasonNumber, it.seasonLevel, it.numWins, if (it.seasonNumber >= 19) " (%,d crowned)".format(it.numRoyalRoyales) else "")
			} else {
				"%s `%2d` Level %,d, Tier %,d, %,d wins".format((if (it.purchasedVIP) battlePassEmote else freePassEmote)?.asMention, it.seasonNumber, it.seasonLevel, it.bookLevel, it.numWins)
			}
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun getXpToNextLevel(defData: AthenaSeasonItemDefinition?, level: Int): Int {
		if (defData == null) {
			return 0
		}
		if (level >= (defData.NumBookLevels ?: 100)) {
			defData.SeasonXpOnlyExtendedCurve?.value
				?.findRowMapped<AthenaExtendedXPCurveEntry>(FName(level.toString()))
				?.let { return it.XpPerLevel }
		} else {
			defData.SeasonXpCurve?.value
				?.findRowMapped<AthenaSeasonalXPCurveEntry>(FName(level.toString()))
				?.let { return it.XpToNextLevel }
		}
		return 0
	}

	private fun getNextLevelReward(seasonDef: AthenaSeasonItemDefinition?, level: Int, vip: Boolean): FortItemStack? {
		if (seasonDef == null) return null
		val lookupLevel = level + 1
		if (seasonDef.bGenerateBookRewards == true) {
			if (lookupLevel >= (seasonDef.NumBookLevels ?: 100)) {
				val row = seasonDef.SeasonXpOnlyExtendedCurve?.value?.findRowMapped<AthenaExtendedXPCurveEntry>(FName(lookupLevel.toString()))
				if (row != null) {
					return row.RewardItemAssetPerLevel.load<FortItemDefinition>()?.let { FortItemStack(it, row.RewardItemCountPerLevel) }
				}
			} else {
				val row = seasonDef.SeasonXpCurve?.value?.findRowMapped<AthenaSeasonalXPCurveEntry>(FName(lookupLevel.toString()))
				if (row != null) {
					return row.RewardItemAsset.load<FortItemDefinition>()?.let { FortItemStack(it, row.RewardItemCount) }
				}
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

	private fun bulk(source: CommandSourceStack, users: Map<String, GameProfile>?): Int {
		source.loading("Getting BR data")
		source.conditionalUseInternalSession()
		val embed = EmbedBuilder().setColor(COLOR_INFO)
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved logins. Please perform `.savelogin` before continuing.")).create()
		}
		if (users != null && devices.none { it.accountId in users }) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved accounts that are matching the name(s).")).create()
		}
		forEachSavedAccounts(source, if (users != null) devices.filter { it.accountId in users } else devices) {
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val stats = athena.stats as AthenaProfileStats
			val ed = "%s %,d ".format((if (stats.book_purchased) battlePassEmote else freePassEmote)?.asMention, stats.level) + seasonCurrencyData.joinToString(" ") {
				val spent = it.getTotal(stats) - it.getBalance(stats)
				textureEmote(it.def.LargePreviewImage.toString())?.asMention + (if (spent >= it.max) "✅" else "❌")
			}
			if (embed.fields.size == 25) {
				source.complete(null, embed.build())
				embed.clearFields()
				source.loading("Getting BR data")
			}
			embed.addField(source.api.currentLoggedIn.displayName, ed, true)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}

val seasonCurrencyData by lazy {
	val seasonData = seasonData ?: return@lazy emptyList()
	val totals = hashMapOf<String, Int>()

	fun gather(entries: List<Lazy<AthenaSeasonItemEntryBase>>) {
		for (entry in entries) {
			val reward = entry.value as? AthenaSeasonItemEntryOfferBase ?: continue
			val price = reward.BattlePassOffer.OfferPriceRowHandle.getRowMapped<AthenaBattlePassOfferPriceRow>()!!
			sumKV(totals, price.CurrencyItemTemplate.PrimaryAssetName.toString().toLowerCase(), price.Cost)
		}
	}

	seasonData.getAdditionalDataOfType<AthenaSeasonItemData_BattleStar>()?.apply {
		PageList?.forEach { gather(it.RewardEntryList) }
		BonusPageList?.forEach { gather(it.RewardEntryList) }
		CustomizationPageList?.forEach { gather(it.RewardEntryList) }
	}

	seasonData.getAdditionalDataOfType<AthenaSeasonItemData_CustomSkin>()?.apply {
		Categories?.forEach { gather(it.Entries) }
	}

	seasonData.SeasonCurrencyMcpDataList.map { SeasonCurrency(it, totals) }
}

class SeasonCurrency(backing: SeasonCurrencyMcpData, totals: Map<String, Int>) {
	val def = backing.CurrencyDefinition.load<FortPersistentResourceItemDefinition>()!!
	val max = totals[def.name.toLowerCase()] ?: 0

	fun getBalance(stats: AthenaProfileStats) = stats.javaClass.getField(def.StatName).get(stats) as Int
	fun getTotal(stats: AthenaProfileStats) = stats.javaClass.getField(def.StatTotalName).get(stats) as Int
}