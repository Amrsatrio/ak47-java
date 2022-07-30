package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.ui.BattlePassViewController
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.AthenaSeasonItemEntryOfferBase.AthenaBattlePassOffer
import com.tb24.fn.model.assetdata.AthenaSeasonItemEntryReward
import com.tb24.fn.model.assetdata.rows.AthenaBattlePassOfferPriceRow
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.athena.ExchangeGameCurrencyForBattlePassOffer
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.util.Utils.sumKV
import com.tb24.fn.util.asItemStack
import com.tb24.fn.util.countMtxCurrency
import com.tb24.fn.util.format
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.math.min

val lockEmote by lazy { textureEmote("/Game/UI/Foundation/Textures/Icons/Locks/T-Icon-Lock-128.T-Icon-Lock-128") }

class BattlePassCommand : BrigadierCommand("battlepass", "Manage your Battle Pass.", arrayOf("bp")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(literal("rewards").executes { rewards(it.source, Type.REWARDS) })
		//.then(literal("quests").executes { rewards(it.source, Type.QUESTS) })
		//.then(literal("bonuses").executes { rewards(it.source, Type.BONUSES) })
		//.then(literal("styles").executes { rewards(it.source, Type.CUSTOMIZATION) })
		.then(literal("buy").executes { purchaseBattlePass(it.source) })
		.then(literal("buyall").executes { purchaseAllOffers(it.source) })

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("rewards", "Shows Battle Pass rewards.").executes { rewards(it, Type.REWARDS) })
		//.then(subcommand("quests", "Shows Battle Pass quest character rewards.").executes { rewards(it, Type.QUESTS) })
		//.then(subcommand("bonuses", "Shows Battle Pass bonus rewards.").executes { rewards(it, Type.BONUSES) })
		//.then(subcommand("styles", "Shows Battle Pass styles rewards.").executes { rewards(it, Type.CUSTOMIZATION) })
		.then(subcommand("buy", "Purchase Battle Pass or levels.").executes(::purchaseBattlePass))

	private fun rewards(source: CommandSourceStack, type: Type): Int {
		source.ensureSession()
		source.loading("Getting BR data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val context = BattlePassViewController(source.api.profileManager.getProfileData("athena"))
		val sections = when (type) {
			Type.REWARDS -> context.rewards
			Type.QUESTS -> context.quests
			Type.BONUSES -> context.bonuses
			Type.CUSTOMIZATION -> context.customization
		}?.sections ?: throw SimpleCommandExceptionType(LiteralMessage("Nothing to see here.")).create()
		source.replyPaginated(sections, 1) { (page), pageNum, pageCount ->
			val embed = EmbedBuilder().setColor(if (context.stats.book_purchased) 0xE1784D else 0x55C5FF)
				.setAuthor("%s / Battle Pass".format(getFriendlySeasonText(context.stats.season_num)), null, Utils.benBotExportAsset(if (context.stats.book_purchased) {
					"/Game/UI/Foundation/Textures/Icons/Items/T-FNBR-BattlePass.T-FNBR-BattlePass"
				} else {
					"/Game/UI/Foundation/Textures/Icons/Items/T-FNBR-BattlePass-Default.T-FNBR-BattlePass-Default"
				}))
				.setTitle(page.title.format()!!.ifEmpty { "Page %,d".format(pageNum + 1) })
				.setDescription(if (page.isUnlocked) {
					"**%,d** / %,d claimed\n`%s`".format(page.purchased, page.entries.size, Utils.progress(page.purchased, page.entries.size, 32))
				} else {
					if (page is BattlePassViewController.Page) {
						"%s Claim **%,d more rewards** or reach **Level %,d** to unlock page!".format(lockEmote?.formatted, page.rewardsForUnlock - page.parent.purchased, page.backing.LevelsNeededForUnlock)
					} else {
						"%s Claim **%,d more rewards** to unlock section!".format(lockEmote?.formatted, page.rewardsForUnlock - page.parent.purchased)
					}
				})
				.addField("Offers", page.entries.joinToString("\n") { it.render(context.stats) }, false)
				.addField("Balance", "%s %,d".format(battleStarEmote?.formatted, context.stats.battlestars), false)
				.setFooter("Page %,d of %,d".format(pageNum + 1, pageCount))
			MessageBuilder(embed)
		}
		return Command.SINGLE_SUCCESS
	}

	private fun BattlePassViewController.Entry.render(stats: AthenaProfileStats): String {
		if (backing is AthenaSeasonItemEntryReward) {
			val locked = !parent.isUnlocked || (!backing.bIsFreePassReward && !stats.book_purchased) // TODO RequiredItems
			val offer = backing.BattlePassOffer
			val item = offer.RewardItem.asItemStack()
			val price = offer.OfferPriceRowHandle.getRowMapped<AthenaBattlePassOfferPriceRow>()!!
			val priceText = if (price.Cost != -1) {
				val priceItem = price.asItemStack()
				"%s %,d".format(getItemIconEmoji(priceItem)?.formatted, priceItem.quantity)
			} else {
				"Included!"
			}
			return "`%d` %s%s [%s]".format(index + 1, if (locked) lockEmote?.formatted + " " else "", item.renderWithIcon(), priceText) + if (purchaseRecord != null) " ✅" else ""
		}
		return "Can't render: " + backing.exportType
	}

	private fun AthenaBattlePassOfferPriceRow.asItemStack() = FortItemStack(
		CurrencyItemTemplate.PrimaryAssetType.Name.toString(),
		CurrencyItemTemplate.PrimaryAssetName.toString().toLowerCase(),
		Cost)

	private fun purchaseBattlePass(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting BR data")
		CompletableFuture.allOf(
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()),
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
		).await()
		val balance = countMtxCurrency(source.api.profileManager.getProfileData("common_core"))
		val athena = source.api.profileManager.getProfileData("athena")
		val stats = athena.stats as AthenaProfileStats
		source.client.catalogManager.ensureCatalogData(source.client.internalSession.api)
		val storefront = source.client.catalogManager.catalogData!!.storefronts.first { it.name == "BRSeason" + stats.season_num }
		val offers = mutableListOf<CatalogEntryHolder>()
		val buttons = mutableListOf<Button>()
		val mtxEmote = source.client.discord.getEmojiById(751101530626588713L)!!
		for (option in PurchaseOption.values()) {
			val devName = "BR.Season%d.%s.01".format(stats.season_num, option.offerName)
			val offer = storefront.catalogEntries.first { it.devName == devName }.holder().apply { resolve(source.api.profileManager) }
			offers.add(offer)
			if ((option.maxLevel == -1 || stats.level <= option.maxLevel) && offer.canPurchase && balance >= offer.price.basePrice) {
				buttons.add(Button.secondary(option.name, "%,d \u00b7 %s".format(offer.price.basePrice, option.displayName)).withEmoji(mtxEmote))
			}
		}
		if (buttons.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No Battle Pass offers available.")).create()
		}
		val botMessage = source.complete("**Which Battle Pass offer would you like to purchase?**\nYour level: %s Level %,d\nBalance: %s %,d\n".format(if (stats.book_purchased) "Battle Pass" else "Free Pass", stats.level, Utils.MTX_EMOJI, balance), null, *buttons.chunked(5, ActionRow::of).toTypedArray())
		source.loadingMsg = botMessage
		val interaction = botMessage.awaitMessageComponent(source, AwaitMessageComponentOptions().apply {
			max = 1
			time = 30000
			errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		}).await().first()
		interaction.deferEdit().queue()
		val choice = PurchaseOption.valueOf(interaction.componentId)
		val offer = offers[choice.ordinal]
		var quantity = 1
		if (choice == PurchaseOption.SINGLE_TIER) {
			val limit = min(balance / offer.price.basePrice, 200 - stats.book_level)
			if (limit > 1) {
				source.complete("Enter the number of tiers you want to buy (1 - %,d, ⏱ 60s)".format(limit))
				source.loadingMsg = botMessage
				quantity = source.channel.awaitMessages(source, AwaitMessagesOptions().apply {
					max = 1
					time = 60000
					errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
				}).await().first().contentRaw.toIntOrNull()
					?: throw SimpleCommandExceptionType(LiteralMessage("The provided input is not a number.")).create()
				if (quantity < 1 || quantity > limit) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid input (%,d). Please do the command again.".format(quantity))).create()
				}
			}
		}
		return purchaseOffer(source, offer.ce, quantity)
	}

	private enum class PurchaseOption(val offerName: String, val displayName: String, val maxLevel: Int) {
		BATTLE_PASS("BattlePass", "Battle Pass", -1),
		BP_25_LEVELS("BP25Levels", "25 Levels", 75),
		SINGLE_TIER("SingleTier", "Individual Levels", 99)
	}

	private fun purchaseAllOffers(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting BR data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val stats = athena.stats as AthenaProfileStats
		val context = BattlePassViewController(athena)
		val result = GatherOffersResult(stats)
		context.rewards?.gatherPurchasableOffers(result)
		context.bonuses?.gatherPurchasableOffers(result)
		context.customization?.gatherPurchasableOffers(result)
		val battlePassOrFreePass = if (stats.book_purchased) "Battle Pass" else "Free Pass"
		val freePassDescription = "Get more rewards by purchasing the Battle Pass! Use `%sbattlepass buy` to purchase it right from the bot.".format(source.prefix)
		if (result.ownedItems == result.totalItems) {
			val embed = source.createEmbed()
				.setTitle("ℹ All %s offers have already been claimed".format(battlePassOrFreePass))
			if (!stats.book_purchased) {
				embed.setDescription(freePassDescription)
			}
			source.complete(null, embed.build())
			return 0
		}
		val unclaimed = result.totalItems - result.ownedItems
		val remainingAfterPurchase = unclaimed - result.purchasableOffers.size
		val notEnoughDescription = "${result.neededBalances.renderBalanceMap()} needed to claim ${(if (remainingAfterPurchase == result.totalItems) "all **%,d**" else "remaining **%,d**").format(remainingAfterPurchase)} offer(s). Go play more to earn those before the season ends!"
		if (result.purchasableOffers.isEmpty()) {
			source.complete(null, source.createEmbed().setColor(COLOR_ERROR)
				.setTitle("❌ Can't claim anything left")
				.setDescription(notEnoughDescription)
				.build())
			return 0
		}
		source.api.profileManager.dispatchClientCommandRequest(ExchangeGameCurrencyForBattlePassOffer().apply {
			offerItemIdList = result.purchasableOffers.map { it.OfferId }.toTypedArray()
		}, "athena").await()
		val embed = source.createEmbed()
		if (remainingAfterPurchase == 0) {
			embed.setColor(COLOR_SUCCESS)
			embed.setTitle("✅ Claimed all %,d %s offers".format(result.purchasableOffers.size, battlePassOrFreePass))
			embed.setDescription(result.spentBalances.renderBalanceMap() + " spent.")
		} else {
			embed.setColor(COLOR_WARNING)
			embed.setTitle("⚠ Claimed %,d of %,d %s offers".format(result.purchasableOffers.size, unclaimed, battlePassOrFreePass))
			embed.setDescription(result.spentBalances.renderBalanceMap() + " spent. " + notEnoughDescription)
		}
		if (!stats.book_purchased) {
			embed.appendDescription('\n' + freePassDescription)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun Map<String, Int>.renderBalanceMap(): String {
		return entries.joinNiceString { (currencyName, count) ->
			val currencyData = seasonCurrencyData.firstOrNull { it.def.name == currencyName }!!
			"%s **%,d**".format(textureEmote(currencyData.def.LargePreviewImage.toString())?.formatted, count)
		}
	}

	private fun BattlePassViewController.Type.gatherPurchasableOffers(result: GatherOffersResult) {
		val queue = mutableListOf<Pair<BattlePassViewController.Entry, AthenaBattlePassOfferPriceRow>>()
		val deferred = TreeSet<Pair<Int, Pair<BattlePassViewController.Entry, AthenaBattlePassOfferPriceRow>>>() { a, b -> a.first - b.first }
		var typePurchased = purchased
		for (section in sections) {
			val sectionUnlocked = typePurchased >= section.rewardsForUnlock || (section is BattlePassViewController.Page && result.stats.level >= section.backing.LevelsNeededForUnlock)
			queue.clear()
			deferred.clear()
			for (entry in section.entries) {
				val data = entry.backing as? AthenaSeasonItemEntryReward ?: continue
				val price = data.price() ?: continue
				if (price.Cost == -1) { // Included, automatically purchased by the backend
					continue
				}
				++result.totalItems
				if (entry.purchaseRecord != null) {
					++result.ownedItems
					continue
				}
				if (!data.bIsFreePassReward && !result.stats.book_purchased) {
					--result.totalItems
					continue
				}
				if (!sectionUnlocked) {
					result.addUnpurchasable(price.CurrencyItemTemplate.PrimaryAssetName.toString(), price.Cost)
					continue
				}
				if (data.RewardsNeededForUnlock != 0) {
					deferred.add(data.RewardsNeededForUnlock to (entry to price))
				} else {
					queue.add(entry to price)
				}
			}
			deferred.forEach { queue.add(it.second) }
			var sectionPurchased = section.purchased
			for ((entry, price) in queue) {
				val data = entry.backing as AthenaSeasonItemEntryReward
				val currencyName = price.CurrencyItemTemplate.PrimaryAssetName.toString()
				val currencyBalance = result.balances[currencyName] ?: 0
				if (currencyBalance < price.Cost || (data.RewardsNeededForUnlock != 0 && sectionPurchased < data.RewardsNeededForUnlock) || (data.TotalRewardsNeededForUnlock != 0 && typePurchased < data.TotalRewardsNeededForUnlock)) {
					result.addUnpurchasable(currencyName, price.Cost)
					continue
				}
				result.balances[currencyName] = currencyBalance - price.Cost
				result.addPurchasable(data, currencyName, price.Cost)
				++typePurchased
				++sectionPurchased
			}
		}
	}

	private fun AthenaSeasonItemEntryReward.price() = BattlePassOffer.OfferPriceRowHandle?.getRowMapped<AthenaBattlePassOfferPriceRow>()

	private class GatherOffersResult(val stats: AthenaProfileStats) {
		var balances = seasonCurrencyData.associateTo(hashMapOf()) { it.def.name to it.getBalance(stats) }
		var totalItems = 0
		var ownedItems = 0
		val neededBalances = mutableMapOf<String, Int>()
		val spentBalances = mutableMapOf<String, Int>()
		val purchasableOffers = mutableListOf<AthenaBattlePassOffer>()

		fun addPurchasable(data: AthenaSeasonItemEntryReward, currencyName: String, cost: Int) {
			sumKV(spentBalances, currencyName, cost)
			purchasableOffers.add(data.BattlePassOffer)
		}

		fun addUnpurchasable(currencyName: String, cost: Int) {
			sumKV(neededBalances, currencyName, cost)
		}
	}

	private enum class Type {
		REWARDS,
		QUESTS,
		BONUSES,
		CUSTOMIZATION,
	}
}