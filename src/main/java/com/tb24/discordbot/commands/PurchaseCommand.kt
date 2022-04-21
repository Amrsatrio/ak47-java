package com.tb24.discordbot.commands

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.HttpException
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
import com.tb24.fn.model.mcpprofile.commands.campaign.PopulatePrerolledOffers
import com.tb24.fn.model.mcpprofile.commands.commoncore.PurchaseCatalogEntry
import com.tb24.fn.model.mcpprofile.commands.commoncore.SetAffiliateName
import com.tb24.fn.model.mcpprofile.notifications.CatalogPurchaseNotification
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.model.priceengine.QueryOfferPricesPayload
import com.tb24.fn.model.priceengine.QueryOfferPricesPayload.LineOfferReq
import com.tb24.fn.util.*
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
		.then(literal("freebulk")
			.executes { purchaseFree(it.source) }
		)
}

fun purchaseFree(source: CommandSourceStack): Int {
	val devices = source.client.savedLoginsManager.getAll(source.author.id)
	forEachSavedAccounts(source, devices) {
		source.loading("Getting the shop")
		val catalogManager = source.client.catalogManager
		val profileManager = source.api.profileManager
		catalogManager.ensureCatalogData(source.client.internalSession.api)
		CompletableFuture.allOf(
			profileManager.dispatchClientCommandRequest(QueryProfile()),
			profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
		).await()
		val sections = catalogManager.athenaSections.values
		var purchased = StringBuilder()
		var numPurchased = 0
		var numFreeOffers = 0
		for ((_, section) in sections.withIndex()) {
			for (catalogEntry in section.items) {
				if (catalogEntry.offerType == ECatalogOfferType.StaticPrice && (catalogEntry.prices.isEmpty() || catalogEntry.prices.first().currencyType == EStoreCurrencyType.RealMoney)) continue
				val sd = catalogEntry.holder().apply { resolve(profileManager) }
				catalogEntry.prices.forEach {
					if (it.currencyType == EStoreCurrencyType.MtxCurrency && it.finalPrice == 0) {
						numFreeOffers++
						if (sd.canPurchase) {
							source.api.profileManager.dispatchClientCommandRequest(PurchaseCatalogEntry().apply {
								offerId = catalogEntry.offerId
								purchaseQuantity = 1
								currency = it.currencyType
								currencySubType = it.currencySubType
								expectedTotalPrice = 0
								gameContext = "Frontend.ItemShopScreen"
							}).await()
							purchased.append("${sd.ce.devName.substring("[VIRTUAL]".length, sd.ce.devName.lastIndexOf(" for ")).replace("1 x ", "").replace(" x ", " \u00d7 ")}\n")
							numPurchased++
						}
					}
				}
			}
		}
		if (numPurchased != 0) {
			val embed = source.createEmbed()
				.addField("✅ Successfully Purchased", purchased.toString(), false)
				.setColor(0x00FF00)
			source.complete(null, embed.build())
		} else {
			val embed = source.createEmbed()
				.setDescription("❌ %s".format(if (numFreeOffers == 0) "No free offers found" else "You already own all free offers"))
			source.complete(null, embed.build())
		}
	}
	return Command.SINGLE_SUCCESS
}

fun purchaseOffer(source: CommandSourceStack, offer: CatalogOffer, quantity: Int = 1, priceIndex: Int = -1): Int {
	var priceIndex = priceIndex
	source.loading("Preparing your purchase")
	val profileManager = source.api.profileManager
	CompletableFuture.allOf(
		profileManager.dispatchClientCommandRequest(QueryProfile()),
		profileManager.dispatchClientCommandRequest(QueryProfile(), if (offer.__ak47_storefront.startsWith("BR")) "athena" else "campaign") // there must be a better way to do this
	).await()
	if (priceIndex < 0) { // find a free price
		priceIndex = offer.prices.indexOfFirst { it.currencyType != EStoreCurrencyType.RealMoney && it.basePrice == 0 }
	}
	if (priceIndex < 0 && offer.prices.size > 1) { // ask which currency to use
		val priceSelectionEbd = source.createEmbed().setColor(BrigadierCommand.COLOR_WARNING)
			.setTitle("How do you want to pay?")
			.addField("Prices", offer.prices.joinToString("\n") { it.render(quantity) }, true)
			.addField("Balances", offer.prices.joinToString("\n") { it.getAccountBalanceText(profileManager) }, true)
		val buttons = offer.prices.mapIndexed { i, price ->
			val emote = price.emote()!!
			Button.of(ButtonStyle.SECONDARY, i.toString(), Emoji.fromEmote(emote))
		}
		val priceSelectionMsg = source.complete(null, priceSelectionEbd.build(), ActionRow.of(buttons))
		priceIndex = priceSelectionMsg.awaitOneInteraction(source.author).componentId.toInt()
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
		return realMoneyPurchase(source, offer, sd)
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
			.renewAffiliateAndPopulateMtxFields(source, price)
		val warnings = mutableListOf<String>()
		if (isUndoUnderCooldown(profileManager.getProfileData("common_core"), offer.offerId)) {
			warnings.add(L10N.format("purchase.undo_cooldown_warning"))
		}
		if (!offer.refundable) {
			warnings.add("This purchase is not eligible for refund.")
		}
		embed.setDescription(warnings.joinToString("\n") { "⚠ $it" })
		confirmed = source.complete(null, embed.build(), confirmationButtons()).awaitConfirmation(source.author).await()
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
		val commonCore = profileManager.getProfileData("common_core")
		val successEmbed = source.createEmbed().setColor(BrigadierCommand.COLOR_SUCCESS)
			.setTitle("✅ " + L10N.format("purchase.success.title"))
			.addFieldSeparate(L10N.format("purchase.success.received"), results.toList(), 0) { it.asItemStack().render(showType = true, showRarity = if (results.size > 10) RARITY_SHOW_DEFAULT_EMOTE else RARITY_SHOW) }
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

private fun realMoneyPurchase(source: CommandSourceStack, offer: CatalogOffer, sd: CatalogEntryHolder): Int {
	if (sd.getMeta("IsSubscription").equals("true", true)) {
		//throw SimpleCommandExceptionType(LiteralMessage("${sd.friendlyName} is a subscription offer. Support for subscription offers will be added in a future update.")).create()
	}
	val epicAppStoreId = offer.appStoreId?.getOrNull(EAppStore.EpicPurchasingService.ordinal)
		?: throw SimpleCommandExceptionType(LiteralMessage("${sd.friendlyName} can't be purchased using Epic Direct Payment, which is the only payment method supported by ${source.jda.selfUser.name}.")).create()
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
	val embed = EmbedBuilder().setColor(BrigadierCommand.COLOR_INFO)
		.populateOffer(storeOffer, false)
		.addField("Price", priceFormatter.format(rmPrice.discountPrice / 100.0) + (if (rmPrice.originalPrice != rmPrice.discountPrice) " ~~" + priceFormatter.format(rmPrice.originalPrice / 100.0) + "~~" else "") + if (rmPrice.vatRate > 0.0) '\n' + "VAT included if applicable" else "", false)
	if (true || rmPrice.discountPrice > 0) {
		// Method 1: Let user access the payment UI
		// Will not work on non EGS offers when they've reached some traffic threshold
		val purchaseToken = generatePurchaseToken(source, epicAppStoreId)
		val purchaseLink = "https://payment-website-pci.ol.epicgames.com/payment/v1/purchase?purchaseToken=$purchaseToken&uePlatform=FNGame"
		source.complete("Visit this link to purchase the item shown below:\n${source.generateUrl(purchaseLink)}", embed.build())
	} else {
		source.loading("Purchasing " + storeOffer?.title)

		// Method 2: Use the orders service directly
		// Only works for free offers, but goodbye when you encounter a captcha

		// Quick purchase API no longer works
		/*val quickPurchasePayload = JsonObject().apply {
			addProperty("salesChannel", "Launcher-purchase-client")
			addProperty("entitlementSource", "Launcher-purchase-client")
			addProperty("returnSplitPaymentItems", false)
			add("lineOffers", JsonArray().apply {
				add(JsonObject().apply {
					addProperty("offerId", epicAppStoreId)
					addProperty("quantity", 1)
					addProperty("namespace", "fn")
				})
			})
		}
		val response = source.session.getApiForOtherClient(EAuthClient.LAUNCHER_APP_CLIENT_2).okHttpClient.newCall(Request.Builder()
			.url("https://orderprocessor-public-service-ecomprod01.ol.epicgames.com/orderprocessor/api/shared/accounts/${source.api.currentLoggedIn.id}/orders/quickPurchase")
			.post(RequestBody.create(MediaType.get("application/json"), quickPurchasePayload.toString()))
			.build()).exec().to<JsonObject>()*/
		// Response is always {"quickPurchaseStatus":"CHECKOUT"}

		// Use payment-website-pci API
		val purchaseToken = generatePurchaseToken(source, epicAppStoreId)
		val okHttpClient = OkHttpClient()
		val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.110 Safari/537.36"
		val orderPreviewPayload = JsonObject().apply {
			addProperty("useDefault", true)
			addProperty("setDefault", false)
			addProperty("namespace", "fn")
			add("country", null)
			add("countryName", null)
			add("orderId", null)
			add("orderComplete", null)
			add("orderError", null)
			add("orderPending", null)
			add("offers", JsonArray().apply {
				add(epicAppStoreId)
			})
			addProperty("offerPrice", "")
		}
		val orderPreviewResponse = okHttpClient.newCall(Request.Builder()
			.url("https://payment-website-pci.ol.epicgames.com/purchase/order-preview")
			.post(orderPreviewPayload.toString().toRequestBody("application/json".toMediaType()))
			.addHeader("User-Agent", userAgent)
			.addHeader("x-requested-with", purchaseToken)
			.build()).execute().handleResponse()
		if (!orderPreviewResponse.has("syncToken")) {
			throw SimpleCommandExceptionType(LiteralMessage("orderPreviewResponse.syncToken == null")).create()
		}
		val confirmOrderPayload = JsonObject().apply {
			addProperty("useDefault", true)
			addProperty("setDefault", false)
			addProperty("namespace", orderPreviewResponse.getString("namespace"))
			addProperty("country", orderPreviewResponse.getString("country"))
			addProperty("countryName", orderPreviewResponse.getString("countryName"))
			add("orderId", null)
			add("orderComplete", null)
			add("orderError", null)
			add("orderPending", null)
			add("offers", orderPreviewResponse.getAsJsonArray("offers"))
			addProperty("includeAccountBalance", false)
			addProperty("totalAmount", orderPreviewResponse.getAsJsonObject("orderResponse").getInt("totalPrice"))
			addProperty("affiliateId", "")
			addProperty("creatorSource", "")
			addProperty("syncToken", orderPreviewResponse.getString("syncToken"))
		}
		val confirmOrderResponse = okHttpClient.newCall(Request.Builder()
			.url("https://payment-website-pci.ol.epicgames.com/purchase/confirm-order")
			.post(confirmOrderPayload.toString().toRequestBody("application/json".toMediaType()))
			.addHeader("User-Agent", userAgent)
			.addHeader("x-requested-with", purchaseToken)
			.build()).execute().handleResponse()
		if (!confirmOrderResponse.getBoolean("confirmation")) {
			throw SimpleCommandExceptionType(LiteralMessage("orderPreviewResponse.confirmation != true")).create()
		}
		source.complete(null, embed.setTitle("✅ Purchased").build())
	}
	return Command.SINGLE_SUCCESS
}

private fun generatePurchaseToken(source: CommandSourceStack, offerId: String): String {
	val purchaseTokenPayload = JsonObject().apply {
		addProperty("locale", "")
		add("offers", JsonArray().apply {
			add(offerId)
		})
		addProperty("subscriptionSlug", "")
		addProperty("namespace", "fn")
	}
	val response = source.api.okHttpClient.newCall(Request.Builder()
		.url("https://payment-website-pci.ol.epicgames.com/payment/v1/purchaseToken")
		.post(purchaseTokenPayload.toString().toRequestBody("application/json".toMediaType()))
		.build()).exec().to<JsonObject>()
	return response.getString("purchaseToken", "")
}

private fun Response.handleResponse(): JsonObject {
	if (isSuccessful) {
		return to()
	}
	val error = to<JsonObject>().getString("message")
	throw SimpleCommandExceptionType(LiteralMessage(error)).create()
}

enum class EOfferDevsCodeReason {
	None,
	NoCodeSet,
	RenewFailed
}

fun EmbedBuilder.renewAffiliateAndPopulateMtxFields(source: CommandSourceStack, price: CatalogOffer.CatalogItemPrice): EmbedBuilder {
	return if (price.currencyType == EStoreCurrencyType.MtxCurrency) {
		// Renew SAC if it has expired
		val commonCore = source.api.profileManager.getProfileData("common_core")
		val stats = commonCore.stats as CommonCoreProfileStats
		var additional: String? = null
		var offerToUseDevsCode = EOfferDevsCodeReason.None
		if (!stats.mtx_affiliate.isNullOrEmpty() && stats.mtx_affiliate_set_time != null) {
			if (System.currentTimeMillis() > stats.mtx_affiliate_set_time.time + 14L * 24L * 60L * 60L * 1000L) {
				try {
					source.api.profileManager.dispatchClientCommandRequest(SetAffiliateName().apply { affiliateName = stats.mtx_affiliate }, "common_core").await()
					additional = "ℹ " + "Renewed"
				} catch (e: HttpException) {
					if (e.code() == 400 || e.code() == 404) {
						additional = "⚠ " + "Expired, renew failed: " + e.epicError.displayText + '\n' + "Please change supported creator using `%ssac <new creator code>`".format(source.prefix)
						offerToUseDevsCode = EOfferDevsCodeReason.RenewFailed
					} else {
						source.errorTitle = "Failed to renew support-a-creator code"
						throw e
					}
				}
			}
		} else {
			offerToUseDevsCode = EOfferDevsCodeReason.NoCodeSet
		}
		if (offerToUseDevsCode != EOfferDevsCodeReason.None) {
			val devAffiliateName = BotConfig.get().devAffiliateName
			if (devAffiliateName != null) {
				val embed = source.createEmbed().setColor(0x0099FF)
					.setTitle("A little offer")
					.setDescription(when (offerToUseDevsCode) {
						EOfferDevsCodeReason.NoCodeSet -> "You have not yet set your support-a-creator code."
						EOfferDevsCodeReason.RenewFailed -> "Your support-a-creator code (%s) has expired and cannot be renewed.".format(stats.mtx_affiliate)
						else -> throw AssertionError()
					} + " Would you like to support the bot's development by using the developer's creator code (%s)?\n\nYou can set the code to another one's by ignoring this and using `%ssac <new creator code>`.".format(devAffiliateName, source.prefix))
				val useDevsCode = source.complete(null, embed.build(), ActionRow.of(
					Button.of(ButtonStyle.PRIMARY, "positive", "Yes, use code %s".format(devAffiliateName)),
					Button.of(ButtonStyle.SECONDARY, "negative", "No, just proceed")
				)).awaitConfirmation(source.author).await()
				if (useDevsCode) {
					try {
						source.api.profileManager.dispatchClientCommandRequest(SetAffiliateName().apply { affiliateName = devAffiliateName }, "common_core").await()
						additional = "ℹ " + "Thank you for using the developer's code :)"
					} catch (e: HttpException) {
						if (e.code() == 400 || e.code() == 404) {
							additional = "ℹ " + "Developer's code is invalid :("
							BotConfig.get().devAffiliateName = null
						} else {
							source.errorTitle = "Failed to set support-a-creator code"
							throw e
						}
					}
				}
			}
		}
		addField(L10N.format("catalog.mtx_platform"), stats.current_mtx_platform.name, true)
		addField(L10N.format("sac.verb"), (stats.mtx_affiliate?.ifEmpty { null } ?: ("🚫 " + L10N.format("common.none"))) + if (additional != null) "\n$additional" else "", false)
	} else this
}

fun claimFreeLlamas(source: CommandSourceStack): Int {
	val freeLlamas = source.client.catalogManager.freeLlamas
	if (freeLlamas.isEmpty()) {
		return 0//throw SimpleCommandExceptionType(LiteralMessage("There are no free llamas right now.")).create()
	}
	CompletableFuture.allOf(
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile()),
		source.api.profileManager.dispatchClientCommandRequest(PopulatePrerolledOffers(), "campaign")
	).await()
	for (offer in freeLlamas) {
		try {
			val sd = offer.holder().apply { resolve(source.api.profileManager) }
			val numToPurchase = sd.purchaseLimit - sd.purchasesCount
			repeat(numToPurchase) {
				purchaseOffer(source, offer, 1, 0)
			}
		} catch (e: CommandSyntaxException) {
			source.complete(null, source.createEmbed().setDescription("❌ " + e.message).build())
		}
	}
	return Command.SINGLE_SUCCESS
}