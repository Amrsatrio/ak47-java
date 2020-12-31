package com.tb24.discordbot

import android.util.Log2
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.tb24.discordbot.util.render
import com.tb24.discordbot.util.safeGetOneIndexed
import com.tb24.fn.ProfileManager
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.CatalogOffer.CatalogItemPrice
import com.tb24.fn.model.gamesubcatalog.ECatalogOfferType
import com.tb24.fn.model.mcpprofile.attributes.CommonCoreProfileAttributes
import com.tb24.fn.util.CatalogHelper
import com.tb24.fn.util.Utils
import com.tb24.uasset.AssetManager
import me.fungames.jfortniteparse.fort.exports.FortMtxOfferData
import java.util.*
import kotlin.math.max

class CatalogEntryHolder(val ce: CatalogOffer) {
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
			if (defData == null) {
				// item data not found from assets, item is encrypted or new
				compiledNames.add(fromDevName.getOrNull(i) ?: item.templateId)
				continue
			}
			var name = item.displayName ?: item.primaryAssetName
			if (name.isEmpty()) {
				name = item.templateId
			}
			compiledNames.add(name)
		}
		compiledNames
	}
	var purchaseLimit = 0
	var purchasesCount = 0
	private val metaInfo by lazy { ce.metaInfo?.associate { it.key?.toLowerCase(Locale.ENGLISH) to it.value } ?: emptyMap() }

	fun getMeta(key: String) = metaInfo[key.toLowerCase(Locale.ENGLISH)]

	@Throws(CommandSyntaxException::class)
	fun resolve(profileManager: ProfileManager, priceIndex: Int = 0) {
		val commonCore = profileManager.getProfileData("common_core")
		val attrs = commonCore.stats.attributes as CommonCoreProfileAttributes
		owned = false
		ownedItems = hashSetOf()
		price = CatalogItemPrice.NO_PRICE
		if (ce.offerType == ECatalogOfferType.StaticPrice) {
			if (ce.prices.isNotEmpty()) {
				price = ce.prices.safeGetOneIndexed(priceIndex + 1)
			}
			owned = CatalogHelper.isStaticPriceCtlgEntryOwned(profileManager, ce)
		} else if (ce.offerType == ECatalogOfferType.DynamicBundle) {
			price = CatalogItemPrice()
			price.regularPrice = ce.dynamicBundleInfo.regularBasePrice
			price.basePrice = ce.dynamicBundleInfo.discountedBasePrice
			price.currencyType = ce.dynamicBundleInfo.currencyType
			price.currencySubType = ce.dynamicBundleInfo.currencySubType
			for (bundleItem in ce.dynamicBundleInfo.bundleItems) {
				price.regularPrice += bundleItem.regularPrice
				price.basePrice += bundleItem.discountedPrice
				if (CatalogHelper.isItemOwned(profileManager, bundleItem.item.templateId, bundleItem.item.quantity)) {
					ownedItems!!.add(bundleItem.item.templateId)
					price.regularPrice -= bundleItem.alreadyOwnedPriceReduction // TODO maybe this is wrong, additional research is required
					price.basePrice -= bundleItem.alreadyOwnedPriceReduction
				}
			}
			price.regularPrice = max(if (ce.dynamicBundleInfo.floorPrice != null) ce.dynamicBundleInfo.floorPrice else 0, price.regularPrice)
			price.finalPrice = max(if (ce.dynamicBundleInfo.floorPrice != null) ce.dynamicBundleInfo.floorPrice else 0, price.basePrice)
			price.basePrice = price.finalPrice
			if (price.saleType == null && price.regularPrice != price.basePrice) {
				price.saleType = ce.dynamicBundleInfo.displayType
			}
			owned = ownedItems!!.size == ce.dynamicBundleInfo.bundleItems.size
		}
		purchaseLimit = -1
		purchasesCount = 0
		getMeta("EventLimit")?.apply {
			try {
				purchaseLimit = toInt()
			} catch (ignored: NumberFormatException) {
			}
		}
		try {
			if (purchaseLimit >= 0) {
				val purchaseLimitingEventId = getMeta("PurchaseLimitingEventId")
				if (purchaseLimitingEventId != null) {
					for (item in commonCore.items.values) {
						if (item.templateId == "EventPurchaseTracker:generic_instance" && purchaseLimitingEventId == item.attributes["event_instance_id"].asString) {
							val intAsElement = item.attributes.getAsJsonObject("event_purchases")[ce.offerId]
							if (intAsElement != null) {
								purchasesCount += intAsElement.asInt
							}
							break
						}
					}
				}
			} else if (ce.dailyLimit >= 0) {
				purchaseLimit = ce.dailyLimit
				if (System.currentTimeMillis() <= attrs.daily_purchases.lastInterval.time + 24L * 60L * 60L * 1000L) {
					val integer = attrs.daily_purchases.purchaseList[ce.offerId]
					if (integer != null) {
						purchasesCount += integer
					}
				}
			} else if (ce.weeklyLimit >= 0) {
				purchaseLimit = ce.weeklyLimit
				if (System.currentTimeMillis() <= attrs.weekly_purchases.lastInterval.time + 7L * 24L * 60L * 60L * 1000L) {
					val integer = attrs.weekly_purchases.purchaseList[ce.offerId]
					if (integer != null) {
						purchasesCount += integer
					}
				}
			} else if (ce.monthlyLimit >= 0) {
				purchaseLimit = ce.monthlyLimit
				if (System.currentTimeMillis() <= attrs.weekly_purchases.lastInterval.time + 30L * 24L * 60L * 60L * 1000L) {
					val integer = attrs.weekly_purchases.purchaseList[ce.offerId]
					if (integer != null) {
						purchasesCount += integer
					}
				}
			}
		} catch (e: NullPointerException) {
			Log2.w("CatalogEntry", "Failed getting purchase limits", e)
		} catch (e: ClassCastException) {
			Log2.w("CatalogEntry", "Failed getting purchase limits", e)
		}
	}

	val displayAsset by lazy { if (!Utils.isNone(ce.displayAssetPath)) AssetManager.INSTANCE.provider.loadObject(ce.displayAssetPath) as? FortMtxOfferData else null }

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

	val friendlyName by lazy { "${ce.title ?: compiledNames.joinToString(", ")} [${(if (ce.offerType == ECatalogOfferType.DynamicBundle) listOf(price) else ce.prices).joinToString(" | ") { it.render() }}]" }
}