package com.tb24.discordbot.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.commands.BattlePassCommand.EBattlePassPurchaseOption.*
import com.tb24.discordbot.util.*
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.util.countMtxCurrency
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.Button
import java.util.concurrent.CompletableFuture
import kotlin.math.min

class BattlePassCommand : BrigadierCommand("battlepass", "Battle pass.", arrayOf("bp")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(literal("buy").executes { purchaseBattlePass(it.source) })

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
		source.client.catalogManager.ensureCatalogData(source.api)
		val storefront = source.client.catalogManager.catalogData!!.storefronts.first { it.name == "BRSeason" + stats.season_num }
		val offers = mutableListOf<CatalogEntryHolder>()
		val buttons = mutableListOf<Button>()
		val mtxEmote = Emoji.fromEmote(source.client.discord.getEmoteById(751101530626588713L)!!)
		for (option in values()) {
			val devName = "BR.Season%d.%s.01".format(stats.season_num, option)
			val offer = storefront.catalogEntries.first { it.devName == devName }.holder().apply { resolve(source.api.profileManager) }
			offers.add(offer)
			if ((option.maxLevel == -1 || stats.level <= option.maxLevel) && offer.canPurchase && balance >= offer.price.basePrice) {
				buttons.add(Button.secondary(option.name, "%,d \u00b7 %s".format(offer.price.basePrice, option.displayName)).withEmoji(mtxEmote))
			}
		}
		if (buttons.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No Battle Pass offers available.")).create()
		}
		val botMessage = source.complete("**Which Battle Pass offer would you like to purchase?**\nYour level: %s Level %,d\nBalance: %s %,d\n".format(if (stats.book_purchased) "Battle Pass" else "Free Pass", stats.level, Utils.MTX_EMOJI, balance), null, buttons.chunked(5, ActionRow::of))
		source.loadingMsg = botMessage
		val interaction = botMessage.awaitMessageComponentInteractions({ _, user, _ -> user == source.author }, AwaitMessageComponentInteractionsOptions().apply {
			max = 1
			time = 30000
			errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		}).await().first()
		interaction.deferEdit().queue()
		val choice = valueOf(interaction.componentId)
		val offer = offers[choice.ordinal]
		var quantity = 1
		if (choice == SingleTier) {
			val limit = min(balance / offer.price.basePrice, 100 - stats.book_level)
			if (limit > 1) {
				source.complete("Enter the number of tiers you want to buy (1 - %,d, ⏱ 45s)".format(limit))
				source.loadingMsg = botMessage
				quantity = source.channel.awaitMessages({ _, user, _ -> user == source.author }, AwaitMessagesOptions().apply {
					max = 1
					time = 30000
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

	enum class EBattlePassPurchaseOption(val displayName: String, val maxLevel: Int) {
		BattlePass("Battle Pass", -1),
		BP25Levels("25 Levels", 75),
		SingleTier("Individual Levels", 99)
	}
}