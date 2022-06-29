package com.tb24.discordbot.ui

import com.tb24.discordbot.managers.HomebaseManager
import com.tb24.discordbot.managers.managerData
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.textureEmote
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.ClaimCollectedResources
import com.tb24.fn.model.mcpprofile.commands.campaign.PurchaseResearchStatUpgrade
import com.tb24.fn.model.mcpprofile.notifications.CollectedResourceResultNotification
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.fn.util.getDateISO
import me.fungames.jfortniteparse.fort.enums.EFortStatType
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.entities.emoji.Emoji
import java.util.*
import kotlin.math.min
import kotlin.jvm.JvmField as F

class ResearchViewController {
	companion object {
		private val researchSystem by managerData.ResearchSystemUpgradesTable
	}

	lateinit var collectorItem: FortItemStack
	@F var points = 0
	@F var collected = 0
	@F val stats = mutableMapOf<EFortStatType, Stat>()

	@F var collectorStoredValue = 0.0f
	lateinit var collectorLastUpdated: Date
	val collectorFullDate get() = getTimeAtCollectorTarget(collectorLimit)
	@F var collectorPoints = 0
	@F var collectorRatePerSecond = 0.0f
	val collectorRatePerHour get() = (collectorRatePerSecond * 3600.0f).toInt()
	@F var collectorLimit = 0
	@F var pointLimit = 0

	constructor()

	constructor(campaign: McpProfile, homebaseManager: HomebaseManager) {
		populateItems(campaign, homebaseManager)
	}

	@Synchronized
	fun populateItems(campaign: McpProfile, homebaseManager: HomebaseManager): Boolean {
		points = 0
		for (item in campaign.items.values) {
			if (item.templateId == "CollectedResource:Token_collectionresource_nodegatetoken01") {
				collectorItem = item
			} else if (item.templateId == "Token:collectionresource_nodegatetoken01") {
				points += item.quantity
			}
		}
		if (!::collectorItem.isInitialized) {
			return false
		}
		for (statType in arrayOf(EFortStatType.Fortitude, EFortStatType.Offense, EFortStatType.Resistance, EFortStatType.Technology)) {
			stats[statType] = Stat(statType, campaign)
		}
		val homebaseNodeAttrs = homebaseManager.homebaseNodeAttributes
		val collectorAttrs = collectorItem.attributes
		collectorStoredValue = collectorAttrs.get("stored_value").asFloat
		collectorLastUpdated = collectorAttrs.getDateISO("last_updated")
		val secondsSinceLastCollectorUpdate = (System.currentTimeMillis() - collectorLastUpdated.time) / 1000L
		collectorRatePerSecond = homebaseNodeAttrs["rate_per_second_collector_Token_collectionresource_nodegatetoken01"] ?: 0.0f
		collectorLimit = (homebaseNodeAttrs["max_capacity_collector_Token_collectionresource_nodegatetoken01"] ?: 0.0f).toInt()
		collectorPoints = min(collectorLimit, (collectorStoredValue + collectorRatePerSecond * secondsSinceLastCollectorUpdate).toInt())
		pointLimit = (homebaseNodeAttrs["ResearchPointMaxBonus"] ?: 0.0f).toInt()
		return true
	}

	fun getTimeAtCollectorTarget(target: Int): Date {
		val secondsUntilTarget = (target.toFloat() - collectorStoredValue) / collectorRatePerSecond
		return Date(collectorLastUpdated.time + (secondsUntilTarget * 1000.0f).toLong())
	}

	fun collect(api: EpicApi, homebase: HomebaseManager) {
		val response = api.profileManager.dispatchClientCommandRequest(ClaimCollectedResources().apply { collectorsToClaim = arrayOf(collectorItem.itemId) }, "campaign").await()
		collected = response.notifications?.filterIsInstance<CollectedResourceResultNotification>()?.firstOrNull()?.loot?.items?.firstOrNull()?.quantity ?: 0
		val campaignModified = api.profileManager.getProfileData("campaign") // Always self account
		populateItems(campaignModified, homebase)
	}

	fun research(api: EpicApi, homebase: HomebaseManager, statType: EFortStatType) {
		check(statType != EFortStatType.Invalid && stats[statType]!!.canUpgrade())
		api.profileManager.dispatchClientCommandRequest(PurchaseResearchStatUpgrade().apply { statId = statType.name }, "campaign").await()
		val campaignModified = api.profileManager.getProfileData("campaign") // Always self account
		populateItems(campaignModified, homebase)
	}

	inner class Stat(val statType: EFortStatType, campaign: McpProfile) {
		val researchLevel: Int
		val gainToNextLevel: Int
		val currentBonusPersonal: Int
		val currentBonusTeam: Int
		val costToNextLevel: Int
		val statIconEmote: Emoji

		init {
			val s = statType.name.toLowerCase()
			val personal = FName(s + "_personal")
			val personal_cumulative = FName(s + "_personal_cumulative")
			val team = FName(s + "_team")
			val team_cumulative = FName(s + "_team_cumulative")
			researchLevel = (campaign.stats as CampaignProfileStats).research_levels[statType]
			gainToNextLevel = researchSystem.findCurve(personal)!!.eval(researchLevel + 1f).toInt() + researchSystem.findCurve(team)!!.eval(researchLevel + 1f).toInt()
			currentBonusPersonal = researchSystem.findCurve(personal_cumulative)!!.eval(researchLevel.toFloat()).toInt()
			currentBonusTeam = researchSystem.findCurve(team_cumulative)!!.eval(researchLevel.toFloat()).toInt()
			costToNextLevel = getCostToLevel()
			statIconEmote = textureEmote(statType.icon)!!
		}

		fun getCostToLevel(level: Int = researchLevel + 1): Int {
			val cost = FName(statType.name.toLowerCase() + "_cost")
			return researchSystem.findCurve(cost)!!.eval(level.toFloat()).toInt()
		}

		fun canUpgrade() = researchLevel < 120 && points >= costToNextLevel
	}
}