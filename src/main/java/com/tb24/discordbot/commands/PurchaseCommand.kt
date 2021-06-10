package com.tb24.discordbot.commands

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.L10N
import com.tb24.discordbot.commands.arguments.CatalogOfferArgument.Companion.catalogOffer
import com.tb24.discordbot.commands.arguments.CatalogOfferArgument.Companion.getCatalogEntry
import com.tb24.discordbot.util.*
import com.tb24.discordbot.util.Utils
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.EAppStore
import com.tb24.fn.model.gamesubcatalog.ECatalogOfferType
import com.tb24.fn.model.gamesubcatalog.EStoreCurrencyType
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commoncore.PurchaseCatalogEntry
import com.tb24.fn.model.mcpprofile.notifications.CatalogPurchaseNotification
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.model.priceengine.QueryOfferPricesPayload
import com.tb24.fn.model.priceengine.QueryOfferPricesPayload.LineOfferReq
import com.tb24.fn.util.*
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Role
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import java.text.NumberFormat
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture

class PurchaseCommand : BrigadierCommand("purchase", "Purchases a shop entry from the Battle Royale or Save the World Item Shop.", arrayOf("buy", "b")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("item number", catalogOffer())
			.executes { purchaseOffer(it.source, getCatalogEntry(it, "item number")) }
			.then(argument("quantity", integer())
				.executes { purchaseOffer(it.source, getCatalogEntry(it, "item number"), getInteger(it, "quantity")) }
				.then(argument("price index", integer())
					.executes { purchaseOffer(it.source, getCatalogEntry(it, "item number"), getInteger(it, "quantity"), getInteger(it, "price index") - 1) }
				)
			)
		)
}

fun purchaseOffer(source: CommandSourceStack, offer: CatalogOffer, quantity: Int = 1, priceIndex: Int = -1): Int {
	var priceIndex = priceIndex
	source.loading("Preparing your purchase")
	val profileManager = source.api.profileManager
	CompletableFuture.allOf(
		profileManager.dispatchClientCommandRequest(QueryProfile()),
		profileManager.dispatchClientCommandRequest(QueryProfile(), if (offer.__ak47_storefront.startsWith("BR")) "athena" else "campaign") // there must be a better way to do this
	).await()
	var commonCore = profileManager.getProfileData("common_core")
	if (priceIndex < 0) { // find a free price
		priceIndex = offer.prices.indexOfFirst { it.currencyType != EStoreCurrencyType.RealMoney && it.basePrice == 0 }
	}
	if (priceIndex < 0 && offer.prices.size > 1) { // ask which currency to use
		val priceSelectionEbd = source.createEmbed().setColor(BrigadierCommand.COLOR_WARNING)
			.setTitle("How do you want to pay?")
			.addField("Prices", offer.prices.joinToString("\n") { it.render(quantity) }, true)
			.addField("Balances", offer.prices.joinToString("\n") { it.getAccountBalanceText(profileManager) }, true)
		val priceSelectionMsg = source.complete(null, priceSelectionEbd.build())
		val icons = offer.prices.map { it.emote() ?: throw SimpleCommandExceptionType(LiteralMessage(it.render(quantity) + " is missing an emote. Please report this problem to the devs.")).create() }
		icons.forEach { priceSelectionMsg.addReaction(it).queue() }
		val choice = priceSelectionMsg.awaitReactions({ reaction, user, _ -> icons.firstOrNull { it.idLong == reaction.reactionEmote.idLong } != null && user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
			max = 1
			time = 30000L
			errors = arrayOf(CollectorEndReason.TIME)
		}).await().first().reactionEmote.idLong
		priceIndex = icons.indexOfFirst { it.idLong == choice }
		if (priceIndex == -1) {
			throw SimpleCommandExceptionType(LiteralMessage("Invalid input.")).create()
		}
	} else if (priceIndex < 0) { // only one price, just use it
		priceIndex = 0
	}
	val sd = offer.holder().apply { resolve(profileManager, priceIndex) }
	val price = sd.price
	if (sd.purchaseLimit >= 0 && sd.purchasesCount >= sd.purchaseLimit) {
		throw SimpleCommandExceptionType(LiteralMessage("${sd.friendlyName} is sold out.")).create()
	}
	if (sd.owned) {
		throw SimpleCommandExceptionType(LiteralMessage(L10N.format("purchase.failed.owned", sd.friendlyName))).create()
	}
	if (!sd.eligible) {
		throw SimpleCommandExceptionType(LiteralMessage(L10N.format("purchase.failed.ineligible", sd.friendlyName))).create()
	}
	if (price.currencyType == EStoreCurrencyType.RealMoney) {
		if (sd.getMeta("IsSubscription").equals("true", true)) {
			throw SimpleCommandExceptionType(LiteralMessage("${sd.friendlyName} is a subscription offer. Support for subscription offers will be added in a future update.")).create()
		}
		val epicAppStoreId = offer.appStoreId?.getOrNull(EAppStore.EpicPurchasingService.ordinal)
			?: throw SimpleCommandExceptionType(LiteralMessage("${sd.friendlyName} can't be purchased using Epic Direct Payment, which is the only payment method supported by ${source.client.discord.selfUser.name}.")).create()
		val completeAccountData = source.api.accountService.getById(source.api.currentLoggedIn.id).exec().body()!!
		val storeOffer = source.api.catalogService.queryOffersBulk(listOf(epicAppStoreId), false, completeAccountData.country, "en").exec().body()!!.values.firstOrNull()
		val rmPrice = source.api.priceEngineService.queryOfferPrices(QueryOfferPricesPayload().apply {
			accountId = source.api.currentLoggedIn.id
			calculateTax = false
			lineOffers = arrayOf(
				LineOfferReq().also {
					it.offerId = epicAppStoreId
					it.quantity = 1
				}
			)
			country = completeAccountData.country
		}).exec().body()!!.lineOffers.first().price
		val priceFormatter = NumberFormat.getCurrencyInstance()
		priceFormatter.currency = Currency.getInstance(rmPrice.currencyCode)
		val purchaseTokenPayload = JsonObject().apply {
			addProperty("locale", "")
			add("offers", JsonArray().apply {
				add(epicAppStoreId)
			})
			addProperty("subscriptionSlug", "")
			addProperty("namespace", "fn")
		}
		val purchaseToken = source.api.okHttpClient.newCall(Request.Builder()
			.url("https://payment-website-pci.ol.epicgames.com/payment/v1/purchaseToken")
			.post(RequestBody.create(MediaType.get("application/json"), purchaseTokenPayload.toString()))
			.build()).exec().to<JsonObject>().getString("purchaseToken")
		val purchaseLink = "https://payment-website-pci.ol.epicgames.com/payment/v1/purchase?purchaseToken=$purchaseToken&uePlatform=FNGame"
		source.complete("Visit this link to purchase the item shown below:\n${source.generateUrl(purchaseLink)}", EmbedBuilder().setColor(BrigadierCommand.COLOR_INFO)
			.populateOffer(storeOffer)
			.addField("Price", priceFormatter.format(rmPrice.discountPrice / 100.0) + (if (rmPrice.originalPrice != rmPrice.discountPrice) " ~~" + priceFormatter.format(rmPrice.originalPrice / 100.0) + "~~" else "") + if (rmPrice.vatRate > 0.0) '\n' + "VAT included if applicable" else "", false)
			.build())
		return Command.SINGLE_SUCCESS
	}
	val accountBalance = price.getAccountBalance(profileManager)
	if (accountBalance < price.basePrice) {
		val priceIcon = price.icon()
		throw SimpleCommandExceptionType(LiteralMessage("Not enough $priceIcon to afford ${sd.friendlyName}. You need $priceIcon ${Formatters.num.format(price.basePrice - accountBalance)} more.\nCurrent balance: $priceIcon ${Formatters.num.format(accountBalance)}")).create()
	}
	val displayData = OfferDisplayData(offer)
	var confirmed = true
	if (sd.price.basePrice > 0) {
		val embed = source.createEmbed()
			.setTitle(L10N.format("purchase.confirmation.title"))
			.addField(L10N.format("catalog.items"), if (sd.compiledNames.isNotEmpty()) sd.compiledNames.mapIndexed { i, s ->
				val strike = if (offer.offerType == ECatalogOfferType.DynamicBundle && profileManager.isItemOwned(offer.itemGrants[i].templateId, offer.itemGrants[i].quantity)) "~~" else ""
				strike + s + strike
			}.joinToString("\n") else offer.devName ?: offer.offerId, false)
			.addField(L10N.format("catalog.quantity"), Formatters.num.format(quantity), false)
			.addField(L10N.format("catalog.total_price"), price.render(quantity), true)
			.addField(L10N.format("catalog.balance"), price.getAccountBalanceText(profileManager), true)
			.setThumbnail(Utils.benBotExportAsset(displayData.imagePath))
			.setColor(displayData.presentationParams?.vector?.get("Background_Color_B") ?: Role.DEFAULT_COLOR_RAW)
		if (price.currencyType == EStoreCurrencyType.MtxCurrency) {
			embed.addField(L10N.format("catalog.mtx_platform"), (commonCore.stats as CommonCoreProfileStats).current_mtx_platform.name, true)
				.addField(L10N.format("sac.verb"), getAffiliateNameRespectingSetDate(commonCore) ?: "🚫 " + L10N.format("common.none"), false)
		}
		val warnings = mutableListOf<String>()
		if (isUndoUnderCooldown(profileManager.getProfileData("common_core"), offer.offerId)) {
			warnings.add(L10N.format("purchase.undo_cooldown_warning"))
		}
		if (!offer.refundable) {
			warnings.add("This purchase is not eligible for refund.")
		}
		embed.setDescription(warnings.joinToString("\n") { "⚠ $it" })
		confirmed = source.complete(null, embed.build()).yesNoReactions(source.author).await()
	}
	if (confirmed) {
		source.errorTitle = "Purchase Failed"
		source.loading("Purchasing ${sd.friendlyName}")
		val response = source.api.profileManager.dispatchClientCommandRequest(PurchaseCatalogEntry().apply {
			offerId = offer.offerId
			purchaseQuantity = quantity
			currency = price.currencyType
			currencySubType = price.currencySubType
			expectedTotalPrice = quantity * price.basePrice
			gameContext = "Frontend.ItemShopScreen"
		}).await()
		val results = response.notifications.filterIsInstance<CatalogPurchaseNotification>().firstOrNull()?.lootResult?.items ?: emptyArray()
		commonCore = profileManager.getProfileData("common_core")
		val successEmbed = source.createEmbed().setColor(BrigadierCommand.COLOR_SUCCESS)
			.setTitle("✅ " + L10N.format("purchase.success.title"))
			.addFieldSeparate(L10N.format("purchase.success.received"), results.toList(), 0) { it.asItemStack().render() }
			.addField(L10N.format("purchase.success.final_balance"), price.getAccountBalanceText(profileManager), false)
			.setTimestamp(Instant.now())
		if (offer.refundable && !isUndoUnderCooldown(commonCore, offer.offerId)) {
			successEmbed.setDescription(L10N.format("purchase.success.undo_instruction", source.prefix))
		}
		source.complete(null, successEmbed.build())
		return Command.SINGLE_SUCCESS
	} else {
		throw SimpleCommandExceptionType(LiteralMessage("Purchase canceled.")).create()
	}
}