package com.tb24.discordbot

import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.tb24.discordbot.commands.OfferDisplayData
import com.tb24.discordbot.util.render
import com.tb24.discordbot.util.safeGetOneIndexed
import com.tb24.fn.ProfileManager
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.CatalogOffer.CatalogItemPrice
import com.tb24.fn.model.gamesubcatalog.ECatalogOfferType
import com.tb24.fn.model.gamesubcatalog.ECatalogSaleType
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats.PurchaseList
import com.tb24.fn.util.CatalogHelper
import com.tb24.fn.util.Utils
import com.tb24.fn.util.getInt
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortMtxOfferData
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.max

class CatalogEntryHolder(val ce: CatalogOffer) {
	companion object {
		val LOGGER: Logger = LoggerFactory.getLogger("CatalogEntryHolder")
	}

	private var offerData: FortMtxOfferData? = null
	var owned = false
	var ownedItems: MutableSet<String>? = null
	var price: CatalogItemPrice = CatalogItemPrice.NO_PRICE
	val compiledNames by lazy {
		val fromDevName = try {
			ce.devName.substring("[VIRTUAL]".length, ce.devName.lastIndexOf(" for ")).replace("1 x ", "").replace(" x ", " \u00d7 ").split(", ")
		} catch (e: Exception) {
			listOf(ce.devName)
		}
		val compiledNames = mutableListOf<String>()
		for (i in ce.itemGrants.indices) {
			val item = ce.itemGrants[i]
			val defData = item.defData
			val name = if (defData == null) {
				// item data not found from assets, item is encrypted or new
				fromDevName.getOrNull(i) ?: item.templateId
			} else {
				val displayName = item.displayName ?: item.primaryAssetName
				displayName.ifEmpty { item.templateId }
			}
			compiledNames.add(if (item.quantity != 1) "(x%,d) %s".format(item.quantity, name) else name)
		}
		compiledNames
	}
	var purchaseLimit = 0
	var purchasesCount = 0
	private val metaInfo by lazy { ce.metaInfo?.associate { it.key?.toLowerCase(Locale.ROOT) to it.value } ?: emptyMap() }

	fun getMeta(key: String) = metaInfo[key.toLowerCase(Locale.ROOT)]

	@Throws(CommandSyntaxException::class)
	fun resolve(profileManager: ProfileManager? = null, priceIndex: Int = 0, resolveOwnership: Boolean = true) {
		owned = false
		ownedItems = hashSetOf()
		price = CatalogItemPrice.NO_PRICE
		if (ce.offerType == ECatalogOfferType.StaticPrice) {
			if (ce.prices.isNotEmpty()) {
				price = ce.prices.safeGetOneIndexed(priceIndex + 1)
			}
			owned = if (resolveOwnership && profileManager != null) CatalogHelper.isStaticPriceCtlgEntryOwned(profileManager, ce) else false
		} else if (ce.offerType == ECatalogOfferType.DynamicBundle) {
			price = CatalogItemPrice()
			price.regularPrice = ce.dynamicBundleInfo.regularBasePrice
			price.basePrice = ce.dynamicBundleInfo.discountedBasePrice
			price.currencyType = ce.dynamicBundleInfo.currencyType
			price.currencySubType = ce.dynamicBundleInfo.currencySubType
			for (bundleItem in ce.dynamicBundleInfo.bundleItems) {
				price.regularPrice += bundleItem.regularPrice
				price.basePrice += bundleItem.discountedPrice
				if (resolveOwnership && profileManager != null && CatalogHelper.isItemOwned(profileManager, bundleItem.item.templateId, bundleItem.item.quantity)) {
					ownedItems!!.add(bundleItem.item.templateId)
					price.regularPrice -= bundleItem.alreadyOwnedPriceReduction
					price.basePrice -= bundleItem.alreadyOwnedPriceReduction
				}
			}
			val floorPrice = ce.dynamicBundleInfo.floorPrice ?: 0
			price.regularPrice = max(floorPrice, price.regularPrice)
			price.finalPrice = max(floorPrice, price.basePrice)
			price.basePrice = price.finalPrice
			if (price.saleType == ECatalogSaleType.NotOnSale && price.regularPrice != price.basePrice) {
				price.saleType = ce.dynamicBundleInfo.displayType
			}
			owned = ownedItems!!.size == ce.dynamicBundleInfo.bundleItems.size
		}
		purchaseLimit = getMeta("EventLimit")?.toIntOrNull() ?: -1
		purchasesCount = 0
		val commonCore = profileManager?.getProfileData("common_core") ?: return
		val stats = commonCore.stats as CommonCoreProfileStats
		if (purchaseLimit >= 0) { // from EventLimit
			val purchaseLimitingEventId = getMeta("PurchaseLimitingEventId")
			if (purchaseLimitingEventId != null) {
				val eventPurchaseTracker = commonCore.items.values.firstOrNull { it.templateId == "EventPurchaseTracker:generic_instance" && it.attributes["event_instance_id"].asString == purchaseLimitingEventId }
				purchasesCount += eventPurchaseTracker?.attributes?.getAsJsonObject("event_purchases")?.getInt(ce.offerId) ?: 0
			}
		} else if (ce.dailyLimit >= 0) {
			purchaseLimit = ce.dailyLimit
			purchasesCount += stats.daily_purchases.getPurchasesCount(1L)
		} else if (ce.weeklyLimit >= 0) {
			purchaseLimit = ce.weeklyLimit
			purchasesCount += stats.weekly_purchases.getPurchasesCount(7L)
		} else if (ce.monthlyLimit >= 0) {
			purchaseLimit = ce.monthlyLimit
			purchasesCount += stats.weekly_purchases.getPurchasesCount(30L)
		}
	}

	private inline fun PurchaseList.getPurchasesCount(hours: Long): Int {
		return if (System.currentTimeMillis() <= (lastInterval?.time ?: return 0) + hours * 24L * 60L * 60L * 1000L) purchaseList?.get(ce.offerId) ?: 0 else 0
	}

	val displayAsset by lazy { if (!Utils.isNone(ce.displayAssetPath)) loadObject<FortMtxOfferData>(ce.displayAssetPath) else null }

	/*fun getDisplayPrice(qty: Int): String {
		if (price == Price.NO_PRICE) {
			return "\u2014"
		}
		val s = Formatters.num.format(qty * price.basePrice.toLong())
		if (price.currencyType != EStoreCurrencyType.RealMoney || TextUtils.isEmpty(ce.appStoreId[1])) {
			return s
		}
		val catalogOffer = catalogOffer ?: return s
		val nf = NumberFormat.getCurrencyInstance()
		nf.currency = Currency.getInstance(catalogOffer.currencyCode)
		return nf.format(qty * catalogOffer.basePrice / 100.0f.toDouble())
	}

	val catalogOffer by lazy { if (ce.appStoreId.isNotEmpty()) FortCatalogResponse.sCatalogOffersMap[ce.appStoreId[EAppStore.EpicPurchasingService.ordinal]] else null }*/

	val friendlyName by lazy {
		val displayData = OfferDisplayData(ce, loadDAV2 = false)
		"${displayData.title ?: compiledNames.joinToString(", ")} [${(if (ce.offerType == ECatalogOfferType.DynamicBundle) listOf(price) else ce.prices).joinToString(" | ") { it.render() }}]"
	}
}