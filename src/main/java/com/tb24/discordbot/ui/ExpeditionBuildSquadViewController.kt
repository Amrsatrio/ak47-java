package com.tb24.discordbot.ui

import com.tb24.discordbot.managers.HomebaseManager
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.commands.campaign.StartExpedition
import com.tb24.fn.model.mcpprofile.item.FortExpeditionItem
import com.tb24.fn.util.Utils
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortExpeditionItemDefinition
import me.fungames.jfortniteparse.fort.objects.rows.FortCriteriaRequirementData
import me.fungames.jfortniteparse.fort.objects.rows.Recipe
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import java.util.*

class ExpeditionBuildSquadViewController(val expedition: FortItemStack, homebase: HomebaseManager) {
	val attrs = expedition.getAttributes(FortExpeditionItem::class.java)
	val defData = expedition.defData as FortExpeditionItemDefinition
	val recipe = defData.ExpeditionRules.getRowMapped<Recipe>()!!
	val type = recipe.RequiredCatalysts.first().toString()
	val criteriaRequirements by lazy {
		val criteriaRequirementsTable = loadObject<UDataTable>("/SaveTheWorld/Expeditions/CriteriaRequirements/ExpeditionCriteriaRequirements")!!
		attrs.expedition_criteria.map { criteriaRequirementsTable.findRowMapped<FortCriteriaRequirementData>(FName.dummy(it))!! }
	}
	val squadChoices = mutableListOf<HomebaseManager.Squad>()
	lateinit var squadController: SquadViewController
		private set
	val squad get() = squadController.squad

	init {
		val squadIds = when (type) {
			"Expedition.Land" -> arrayOf("Squad_Expedition_ExpeditionSquadOne", "Squad_Expedition_ExpeditionSquadTwo")
			"Expedition.Sea" -> arrayOf("Squad_Expedition_ExpeditionSquadThree", "Squad_Expedition_ExpeditionSquadFour")
			"Expedition.Air" -> arrayOf("Squad_Expedition_ExpeditionSquadFive", "Squad_Expedition_ExpeditionSquadSix")
			else -> throw AssertionError()
		}
		for (squadId in squadIds) {
			val squad = homebase.squads[squadId.toLowerCase(Locale.ROOT)]!!
			val unlockedStates = BooleanArray(squad.slots.size)
			var hasUnlockedSlot = false
			var occupied = false
			squad.slots.forEachIndexed { i, slot ->
				unlockedStates[i] = slot.unlocked
				hasUnlockedSlot = hasUnlockedSlot || slot.unlocked
				occupied = occupied || slot.item != null
			}
			if (hasUnlockedSlot && !occupied) {
				val clonedSquad = HomebaseManager.Squad(squadId, squad.backing)
				clonedSquad.slots.forEachIndexed { i, slot ->
					slot.unlocked = unlockedStates[i]
				}
				squadChoices.add(clonedSquad)
			}
		}
	}

	fun setSquad(squad: HomebaseManager.Squad) {
		if (squad !in squadChoices) {
			throw IllegalArgumentException("Given squad ${squad.squadId} is not a valid choice")
		}
		this.squadController = SquadViewController(squad)
	}

	fun getSquadRating(): Int {
		val out = hashMapOf<String, Int>()
		for (slot in squad.slots) {
			val item = slot.item ?: continue
			val rating = item.powerLevel
			for (slottingBonus in slot.backing.SlottingBonuses) {
				val attributeName = slottingBonus.AttributeGranted.AttributeName
				val attributeValue = slottingBonus.BonusCurve.eval(rating).toInt()
				Utils.sumKV(out, attributeName, attributeValue)
			}
		}
		check(out.size == 1)
		return out.values.first()
	}

	fun getSuccessChance(squadRating: Int = getSquadRating()) = squadRating / attrs.expedition_max_target_power

	fun generatePayload(): StartExpedition {
		val itemIds = mutableListOf<String>()
		val slotIndices = mutableListOf<Int>()
		var i = 0
		squad.slots.forEach {
			val item = it.item
			if (item != null) {
				itemIds.add(item.itemId)
				slotIndices.add(i++)
			}
		}
		val payload = StartExpedition()
		payload.expeditionId = expedition.itemId
		payload.squadId = squad.squadId
		payload.itemIds = itemIds.toTypedArray()
		payload.slotIndices = slotIndices.toIntArray()
		return payload
	}
}