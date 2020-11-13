package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.L10N
import com.tb24.discordbot.commands.arguments.CatalogEntryArgument.Companion.catalogEntry
import com.tb24.discordbot.commands.arguments.CatalogEntryArgument.Companion.getCatalogEntry
import com.tb24.discordbot.util.*
import com.tb24.fn.model.EStoreCurrencyType
import com.tb24.fn.model.FortCatalogResponse.CatalogEntry
import com.tb24.fn.model.FortCatalogResponse.ECatalogOfferType
import com.tb24.fn.model.mcpprofile.attributes.CommonCoreProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.PurchaseCatalogEntry
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.notifications.CatalogPurchaseNotification
import com.tb24.fn.util.CatalogHelper
import com.tb24.fn.util.CatalogHelper.isItemOwned
import com.tb24.fn.util.Formatters
import java.time.Instant
import java.util.concurrent.CompletableFuture

class PurchaseCommand : BrigadierCommand("purchase", "Purchases a shop entry from the Battle Royale or Save the World Item Shop.", arrayOf("buy", "b")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("item number", catalogEntry())
			.executes { execute(it.source, getCatalogEntry(it, "item number")) }
			.then(argument("quantity", integer())
				.executes { execute(it.source, getCatalogEntry(it, "item number"), getInteger(it, "quantity")) }
				.then(argument("price index", integer())
					.executes { execute(it.source, getCatalogEntry(it, "item number"), getInteger(it, "quantity"), getInteger(it, "price index") - 1) }
					/*.then(argument("more", integer())
						.then(argument("and more", integer())
							.then(argument("and more again", integer())
								.executes { Command.SINGLE_SUCCESS }
							)
						)
					)*/
				)
			)
		)

	private fun execute(source: CommandSourceStack, catalogEntry: CatalogEntry, quantity: Int = 1, priceIndex: Int = -1): Int {
		var priceIndex = priceIndex
		source.loading("Preparing your purchase")
		val profileManager = source.api.profileManager
		CompletableFuture.allOf(
			profileManager.dispatchClientCommandRequest(QueryProfile()),
			profileManager.dispatchClientCommandRequest(QueryProfile(), if (catalogEntry.__ak47_storefront.startsWith("BR")) "athena" else "campaign") // there must be a better way to do this
		).await()
		var commonCore = profileManager.getProfileData("common_core")
		if (priceIndex < 0 && catalogEntry.prices.size > 1) {
			val priceSelectionEbd = source.createEmbed()
				.setTitle("How do you want to pay?")
				.setColor(0x4BDA74)
				.addField("Prices", catalogEntry.prices.joinToString("\n") { it.render(quantity) }, true)
				.addField("Balances", catalogEntry.prices.joinToString("\n") { it.getAccountBalanceText(profileManager) }, true)
			val priceSelectionMsg = source.complete(null, priceSelectionEbd.build())
			val icons = catalogEntry.prices.map { it.emote() ?: throw SimpleCommandExceptionType(LiteralMessage(it.render(quantity) + " is missing an emote. Please report this problem to the devs.")).create() }
			icons.forEach { priceSelectionMsg.addReaction(it).queue() }
			try {
				val choice = priceSelectionMsg.awaitReactions({ reaction, user, _ -> icons.firstOrNull { it.idLong == reaction.reactionEmote.idLong } != null && user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
					max = 1
					time = 30000
					errors = arrayOf(CollectorEndReason.TIME)
				}).await().values.first().reactionEmote.idLong
				priceIndex = icons.indexOfFirst { it.idLong == choice }
				if (priceIndex == -1) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid input")).create()
				}
			} catch (e: CollectorException) {
				throw SimpleCommandExceptionType(LiteralMessage("Timed out while waiting for your response.")).create()
			}
		} else if (priceIndex < 0) {
			priceIndex = 0
		}
		val sd = catalogEntry.holder().apply { resolve(profileManager, priceIndex) }
		val price = sd.price
		if (sd.owned) {
			throw SimpleCommandExceptionType(LiteralMessage(L10N.format("purchase.failed.owned", sd.friendlyName))).create()
		}
		if (price.currencyType == EStoreCurrencyType.RealMoney) {
			throw SimpleCommandExceptionType(LiteralMessage("${sd.friendlyName} is a Real Money offer. Support for Real Money offers will be added in a future update.")).create()
		}
		val accountBalance = price.getAccountBalance(profileManager)
		if (accountBalance < price.basePrice) {
			val priceIcon = price.icon()
			throw SimpleCommandExceptionType(LiteralMessage("Not enough $priceIcon to afford ${sd.friendlyName}. You need $priceIcon ${Formatters.num.format(price.basePrice - accountBalance)} more.\nCurrent balance: $priceIcon ${Formatters.num.format(accountBalance)}")).create()
		}
		var confirmed = true
		if (sd.price.basePrice > 0) {
			val embed = source.createEmbed()
				.setColor(0x4BDA74)
				.setTitle(L10N.format("purchase.confirmation.title"))
				.addField(L10N.format("catalog.items"), sd.compiledNames.mapIndexed { i, s ->
					val strike = if (catalogEntry.offerType == ECatalogOfferType.DynamicBundle && isItemOwned(profileManager, catalogEntry.itemGrants[i].templateId, catalogEntry.itemGrants[i].quantity)) "~~" else ""
					strike + s + strike
				}.joinToString("\n"), false)
				.addField(L10N.format("catalog.quantity"), Formatters.num.format(quantity), false)
				.addField(L10N.format("catalog.total_price"), price.render(quantity), true)
				.addField(L10N.format("catalog.balance"), price.getAccountBalanceText(profileManager), true)
			if (price.currencyType == EStoreCurrencyType.MtxCurrency) {
				embed.addField(L10N.format("catalog.mtx_platform"), (commonCore.stats.attributes as CommonCoreProfileAttributes).current_mtx_platform.name, true)
					.addField(L10N.format("sac.verb"), CatalogHelper.getAffiliateNameRespectingSetDate(commonCore) ?: L10N.format("common.none"), false)
			}
			val warnings = mutableListOf<String>()
			if (CatalogHelper.isUndoUnderCooldown(profileManager.getProfileData("common_core"), catalogEntry.offerId)) {
				warnings.add(L10N.format("purchase.undo_cooldown_warning"))
			}
			if (!catalogEntry.refundable) {
				warnings.add("This purchase is not eligible for refund.")
			}
			embed.setDescription(warnings.joinToString("\n") { "⚠ $it" })
			confirmed = source.complete(null, embed.build()).yesNoReactions(source.author).await()
		}
		if (confirmed) {
			source.errorTitle = "Purchase Failed"
			source.loading("Purchasing ${sd.friendlyName}")
			val response = source.api.profileManager.dispatchClientCommandRequest(PurchaseCatalogEntry().apply {
				offerId = catalogEntry.offerId
				purchaseQuantity = quantity
				currency = price.currencyType
				currencySubType = price.currencySubType
				expectedTotalPrice = quantity * price.basePrice
				gameContext = "Frontend.ItemShopScreen"
			}).await()
			val results = response.notifications.filterIsInstance<CatalogPurchaseNotification>().firstOrNull()?.lootResult?.items ?: emptyArray()
			commonCore = profileManager.getProfileData("common_core")
			val successEmbed = source.createEmbed()
				.setTitle("✅ " + L10N.format("purchase.success.title"))
				.setColor(0x4BDA74)
				.addField(L10N.format("purchase.success.received"), if (results.isEmpty()) "No items" else results.joinToString("\n") { it.asItemStack().render() }, false)
				.addField(L10N.format("purchase.success.final_balance"), price.getAccountBalanceText(profileManager), false)
				.setTimestamp(Instant.now())
			if (catalogEntry.refundable && !CatalogHelper.isUndoUnderCooldown(commonCore, catalogEntry.offerId)) {
				successEmbed.setDescription(L10N.format("purchase.success.undo_instruction", source.prefix))
			}
			source.complete(null, successEmbed.build())
			return Command.SINGLE_SUCCESS
		} else {
			throw SimpleCommandExceptionType(LiteralMessage("Purchase canceled.")).create()
		}
	}
}