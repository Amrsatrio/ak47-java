package com.tb24.discordbot.managers

import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.getStackTraceAsString
import com.tb24.discordbot.util.to
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortCmsData
import com.tb24.fn.model.FortCmsData.ShopSectionsData
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.gamesubcatalog.CatalogDownload
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import okhttp3.Request
import org.quartz.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

class CatalogManager {
	companion object {
		private val LOGGER: Logger = LoggerFactory.getLogger("CatalogManager")
	}

	var client: DiscordBot? = null
	var catalogData: CatalogDownload? = null
	var sectionsData: ShopSectionsData? = null
	private val stwEvent = ShopSection("Event Store")
	private val stwWeekly = ShopSection("Weekly Store")
	private val llamas = ShopSection("Llamas")
	val athenaSections = mutableMapOf<String, ShopSection>()
	val campaignSections = mutableMapOf("Event" to stwEvent, "Weekly" to stwWeekly, "Llamas" to llamas)
	val purchasableCatalogEntries = mutableListOf<CatalogOffer>()
	private var athenaHash = 0
	private var campaignHash = 0
	private val updateJob = JobBuilder.newJob(UpdateCatalogJob::class.java).withIdentity("updateCatalog").build()
	var freeLlamas = emptyList<CatalogOffer>()

	fun initialize(client: DiscordBot?) {
		this.client = client
		if (client != null) {
			try {
				ensureCatalogData(client.internalSession.api)
			} catch (e: Exception) {
				LOGGER.warn("An error occurred when fetching the catalog for the first time", e)
			}
			updateJob.jobDataMap["client"] = client
			client.scheduler.scheduleJob(updateJob, TriggerBuilder.newTrigger()
				.startAt(Date(DateBuilder.evenHourDateAfterNow().time + 10L * 1000L)) // Give it 10 seconds delay as client time may vary slightly from server
				.withSchedule(SimpleScheduleBuilder.repeatHourlyForever()) // Catalog refreshes hourly
				.build())
		}
	}

	@Synchronized
	fun ensureCatalogData(api: EpicApi, force: Boolean = false): Boolean {
		val firstLoad = catalogData == null
		if (force || firstLoad || System.currentTimeMillis() >= catalogData!!.expiration.time) {
			catalogData = api.fortniteService.storefrontCatalog("en").exec().body()
			sectionsData = api.okHttpClient.newCall(Request.Builder().url("https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game/shop-sections").build()).exec().to<ShopSectionsData>()
			validate(firstLoad)
			LOGGER.info("Loaded catalog. Expires: " + catalogData!!.expiration)
			return true
		}
		return false
	}

	fun validate(firstLoad: Boolean = true) {
		athenaSections.clear()
		sectionsData!!.sectionList.sections.associateTo(athenaSections) {
			val section = ShopSection(it)
			section.sectionData.sectionId to section
		}
		campaignSections.values.forEach { it.items.clear() }
		for (storefront in catalogData!!.storefronts) {
			for (offer in storefront.catalogEntries) {
				offer.__ak47_storefront = storefront.name
				offer.getMeta("EncryptionKey")?.let {
					LOGGER.info("[FortStorefront]: Adding key $it to keychain through store offer ${offer.offerId}")
					client?.keychainTask?.handle(it)
				}
				(athenaSections[offer.getMeta("SectionId") ?: continue] ?: continue).items.add(offer)
				if (offer.getMeta("IsLevelBundle").equals("true", true)) {
					val tierToken = FortItemStack("Token:athenabattlepasstier", offer.getMeta("LevelsToGrant")?.toIntOrNull() ?: 1)
					offer.itemGrants = listOf(tierToken)
					offer.title = "%,d %s".format(tierToken.quantity, tierToken.displayName)
				}
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
		campaignSections.values.forEach { purchasableCatalogEntries.addAll(it.items) }
		for (i in purchasableCatalogEntries.indices) { // assign indices
			purchasableCatalogEntries[i].__ak47_index = i
		}

		// Check for changes and invoke the callbacks when necessary
		val currentAthenaHash = hashSections(athenaSections.filter { it.value.items.isNotEmpty() })
		val currentCampaignHash = hashSections(campaignSections)
		if (athenaHash != currentAthenaHash) {
			athenaHash = currentAthenaHash
			if (!firstLoad) onAthenaCatalogUpdated()
		}
		if (campaignHash != currentCampaignHash) {
			campaignHash = currentCampaignHash
			if (!firstLoad) onCampaignCatalogUpdated()
		}
	}

	private fun hashSections(map: Map<String, ShopSection>) = map.entries.joinToString(",") { (sectionId, section) ->
		sectionId + section.items.joinToString(",", "[", "]") { it.offerId }
	}.hashCode()

	private fun onAthenaCatalogUpdated() {
		client?.postItemShop()
		// TODO wishlist feature where users are reminded when the item(s) they want enter the shop
	}

	private fun onCampaignCatalogUpdated() {
		freeLlamas = llamas.items.filter { it.devName == "RandomFree.FreePack.01" || it.title == "Upgrade Llama (Seasonal Sale Freebie!)" }
		val client = client ?: return
		client.discord.getTextChannelById(BotConfig.get().itemShopChannelId)?.sendMessage("Free llamas: " + freeLlamas.joinToString { "#%,d".format(it.__ak47_index + 1) }.ifEmpty { "🚫 None" })?.queue()
		if (freeLlamas.isNotEmpty()) {
			try {
				client.autoFreeLlamaTask.run()
			} catch (e: Throwable) {
				client.dlog("__**AutoFreeLlamaTask failure**__\n```\n${e.getStackTraceAsString()}```", null)
				client.autoFreeLlamaTask.isRunning.set(false)
			}
		}
	}

	class ShopSection(val sectionData: FortCmsData.ShopSection) {
		val items = mutableListOf<CatalogOffer>()

		constructor(title: String) : this(FortCmsData.ShopSection().apply { sectionDisplayName = title })
	}

	class UpdateCatalogJob : Job {
		override fun execute(context: JobExecutionContext) {
			val client = context.mergedJobDataMap["client"] as DiscordBot
			client.ensureInternalSession()
			client.catalogManager.ensureCatalogData(client.internalSession.api)
			// Note: Don't reschedule the job using catalogData.expiration since it can prevent subsequent scheduled jobs
			// from being executed whenever an exception occurs
		}
	}
}