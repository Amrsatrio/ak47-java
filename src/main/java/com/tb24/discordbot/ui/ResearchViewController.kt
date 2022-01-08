package com.tb24.discordbot.ui

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.textureEmote
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortStatType
import me.fungames.jfortniteparse.ue4.assets.exports.UCurveTable
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.entities.Emote
import kotlin.jvm.JvmField as F

class ResearchViewController {
	companion object {
		private val researchSystem by lazy { loadObject<UCurveTable>("/SaveTheWorld/Research/ResearchSystem.ResearchSystem")!! }
	}

	lateinit var campaign: McpProfile
	@F var resourceCollectorItem: FortItemStack? = null
	@F var points = 0
	@F var collected = 0
	@F val stats = mutableMapOf<EFortStatType, Stat>()

	constructor(campaign: McpProfile) {
		populateItems(campaign)
	}

	@Synchronized
	fun populateItems(campaign: McpProfile) {
		this.campaign = campaign
		points = 0
		for (item in campaign.items.values) {
			if (item.templateId == "CollectedResource:Token_collectionresource_nodegatetoken01") {
				resourceCollectorItem = item
			} else if (item.templateId == "Token:collectionresource_nodegatetoken01") {
				points += item.quantity
			}
		}
		if (resourceCollectorItem == null) {
			throw SimpleCommandExceptionType(LiteralMessage("Please complete the Audition quest (one quest after Stonewood SSD 3) to unlock Research.")).create()
		}
		for (statType in arrayOf(EFortStatType.Fortitude, EFortStatType.Offense, EFortStatType.Resistance, EFortStatType.Technology)) {
			stats[statType] = Stat(statType)
		}
	}

	inner class Stat(statType: EFortStatType) {
		val researchLevel: Int
		val gainToNextLevel: Int
		val currentBonusPersonal: Int
		val currentBonusTeam: Int
		val costToNextLevel: Int
		val statIconEmote: Emote

		init {
			val s = statType.name.toLowerCase()
			val cost = FName(s + "_cost")
			val personal = FName(s + "_personal")
			val personal_cumulative = FName(s + "_personal_cumulative")
			val team = FName(s + "_team")
			val team_cumulative = FName(s + "_team_cumulative")
			researchLevel = (campaign.stats as CampaignProfileStats).research_levels[statType]
			gainToNextLevel = researchSystem.findCurve(personal)!!.eval(researchLevel + 1f).toInt() + researchSystem.findCurve(team)!!.eval(researchLevel + 1f).toInt()
			currentBonusPersonal = researchSystem.findCurve(personal_cumulative)!!.eval(researchLevel.toFloat()).toInt()
			currentBonusTeam = researchSystem.findCurve(team_cumulative)!!.eval(researchLevel.toFloat()).toInt()
			costToNextLevel = researchSystem.findCurve(cost)!!.eval(researchLevel + 1f).toInt()
			statIconEmote = textureEmote(statType.icon)!!
		}

		fun canUpgrade() = researchLevel < 120 && points >= costToNextLevel
	}
}