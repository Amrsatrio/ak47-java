package com.tb24.discordbot.managers

import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.to
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortCmsData
import com.tb24.fn.model.FortCmsData.ShopSectionsData
import com.tb24.fn.model.gamesubcatalog.CatalogDownload
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import okhttp3.Request

class CatalogManager {
	var catalogData: CatalogDownload? = null
	var sectionsData: ShopSectionsData? = null
	val stwEvent = ShopSection("Event Store")
	val stwWeekly = ShopSection("Weekly Store")
	val llamas = ShopSection("Llamas")
	val athenaSections = mutableMapOf<String, ShopSection>()
	val campaignSections = listOf(stwEvent, stwWeekly, llamas)
	val purchasableCatalogEntries = mutableListOf<CatalogOffer>()

	@Synchronized
	fun ensureCatalogData(api: EpicApi, force: Boolean = false): Boolean {
		if (force || catalogData == null || System.currentTimeMillis() >= catalogData!!.expiration.time) {
			catalogData = api.fortniteService.storefrontCatalog("en").exec().body()
			sectionsData = api.okHttpClient.newCall(Request.Builder().url("https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game/shop-sections").build()).exec().to<ShopSectionsData>()
			athenaSections.clear()
			sectionsData!!.sectionList.sections.associateTo(athenaSections) {
				val section = ShopSection(it)
				section.sectionData.sectionId to section
			}
			campaignSections.forEach { it.items.clear() }
			for (storefront in catalogData!!.storefronts) {
				for (offer in storefront.catalogEntries) {
					offer.__ak47_storefront = storefront.name
					offer.getMeta("EncryptionKey")?.let {
						DiscordBot.LOGGER.info("[FortStorefront]: Adding key $it to keychain through store offer ${offer.offerId}")
						DiscordBot.instance?.keychainTask?.handle(it)
					}
					(athenaSections[offer.getMeta("SectionId") ?: continue] ?: continue).items.add(offer)
				}
				when (storefront.name) {
					"STWSpecialEventStorefront" -> stwEvent.items.addAll(storefront.catalogEntries)
					"STWRotationalEventStorefront" -> stwWeekly.items.addAll(storefront.catalogEntries)
					"CardPackStorePreroll",
					"CardPackStoreGameplay" -> llamas.items.addAll(storefront.catalogEntries)
				}
			}
			purchasableCatalogEntries.clear()
			athenaSections.values.forEach { section ->
				section.items.sortByDescending { it.sortPriority ?: 0 }
				purchasableCatalogEntries.addAll(section.items)
			}
			campaignSections.forEach { purchasableCatalogEntries.addAll(it.items) }
			for (i in purchasableCatalogEntries.indices) { // assign indices
				purchasableCatalogEntries[i].__ak47_index = i
			}
			return true
		}
		return false
	}

	class ShopSection(val sectionData: FortCmsData.ShopSection) {
		val items = mutableListOf<CatalogOffer>()

		constructor(title: String) : this(FortCmsData.ShopSection().also { it.sectionDisplayName = title })
	}
}