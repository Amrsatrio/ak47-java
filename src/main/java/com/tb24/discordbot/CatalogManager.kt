package com.tb24.discordbot

import com.tb24.discordbot.util.exec
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortCatalogResponse
import com.tb24.fn.model.FortCatalogResponse.CatalogEntry

class CatalogManager(private val client: DiscordBot) {
	var catalogData: FortCatalogResponse? = null
	val limitedTimeOffers = Section("Limited Time Offers")
	val featuredItems = Section("Featured Items")
	val dailyItems = Section("Daily Items")
	val specialFeatured = Section("Special Offers")
	val specialDaily = Section("\u2014")
	val stwEvent = Section("Event Store")
	val stwWeekly = Section("Weekly Store")
	val llamas = Section("Llamas")
	val athenaCatalogGroups = arrayOf(limitedTimeOffers, featuredItems, dailyItems, specialFeatured, specialDaily)
	val campaignCatalogGroups = arrayOf(stwEvent, stwWeekly, llamas)
	val purchasableCatalogEntries = mutableListOf<CatalogEntry>()

	class Section(val title: String, val items: MutableList<CatalogEntry> = mutableListOf())

	fun ensureCatalogData(api: EpicApi, force: Boolean = false): Boolean {
		if (force || catalogData == null || System.currentTimeMillis() >= catalogData!!.expiration.time) {
			catalogData = api.fortniteService.storefrontCatalog("en").exec().body()
			athenaCatalogGroups.forEach { it.items.clear() }
			campaignCatalogGroups.forEach { it.items.clear() }
			for (storefront in catalogData!!.storefronts) {
				storefront.catalogEntries.onEach { it.__ak47_storefront = storefront.name }
				when (storefront.name) {
					"BRStarterKits" -> limitedTimeOffers.items.addAll(storefront.catalogEntries)
					"BRWeeklyStorefront" -> featuredItems.items.addAll(storefront.catalogEntries)
					"BRDailyStorefront" -> dailyItems.items.addAll(storefront.catalogEntries)
					"BRSpecialFeatured" -> specialFeatured.items.addAll(storefront.catalogEntries)
					"BRSpecialDaily" -> specialDaily.items.addAll(storefront.catalogEntries)
					"STWSpecialEventStorefront" -> stwEvent.items.addAll(storefront.catalogEntries)
					"STWRotationalEventStorefront" -> stwWeekly.items.addAll(storefront.catalogEntries)
					"CardPackStorePreroll",
					"CardPackStoreGameplay" -> llamas.items.addAll(storefront.catalogEntries)
				}
			}
			purchasableCatalogEntries.clear()
			purchasableCatalogEntries.addAll(limitedTimeOffers.items)
			purchasableCatalogEntries.addAll(featuredItems.items)
			purchasableCatalogEntries.addAll(dailyItems.items)
			purchasableCatalogEntries.addAll(specialFeatured.items)
			purchasableCatalogEntries.addAll(specialDaily.items)
			purchasableCatalogEntries.addAll(stwEvent.items)
			purchasableCatalogEntries.addAll(stwWeekly.items)
			purchasableCatalogEntries.addAll(llamas.items)
			for (i in purchasableCatalogEntries.indices) {
				purchasableCatalogEntries[i].__ak47_index = i
			}
			return true
		}
		return false
	}
}