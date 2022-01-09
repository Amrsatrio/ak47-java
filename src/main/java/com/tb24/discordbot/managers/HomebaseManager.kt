package com.tb24.discordbot.managers

import com.tb24.fn.EpicApi
import com.tb24.fn.event.ProfileUpdatedEvent
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.Utils
import com.tb24.fn.util.getString
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortStatType
import me.fungames.jfortniteparse.fort.exports.FortHomebaseManager
import me.fungames.jfortniteparse.fort.exports.FortHomebaseNodeItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortWorkerType
import me.fungames.jfortniteparse.fort.objects.rows.HomebaseNodeGameplayEffectDataTableRow
import me.fungames.jfortniteparse.fort.objects.rows.HomebaseSquad
import me.fungames.jfortniteparse.fort.objects.rows.HomebaseSquad.*
import me.fungames.jfortniteparse.ue4.assets.exports.UCurveTable
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.assets.objects.FStructFallback
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import org.greenrobot.eventbus.Subscribe
import java.util.*

@JvmField val managerData = loadObject<FortHomebaseManager>("/SaveTheWorld/CommandConsole/CommandConsole_Manager")!!
@JvmField val homebaseNodeGameplayEffectDataTable: UDataTable = managerData.HomebaseNodeGameplayEffectDataTable.value
@JvmField val squadsDataTable: UDataTable = managerData.HomebaseSquadDataTable.value
@JvmField val powerPointToRatingConversion = loadObject<UCurveTable>("/SaveTheWorld/Characters/Enemies/DataTables/HomebaseRatingMapping")!!.findCurve(FName("UIMonsterRating"))

class HomebaseManager(val accountId: String, api: EpicApi) {
	@JvmField val squads = LinkedHashMap<String, Squad>(squadsDataTable.rows.size).apply {
		for ((k, v) in squadsDataTable.rows) {
			val squadId = k.text.toLowerCase(Locale.ROOT)
			put(squadId, Squad(squadId, v.mapToClass()))
		}
	}
	@JvmField val bonuses = mutableMapOf<EFortStatType, Int>()
	@JvmField val phoenixBonuses = mutableMapOf<EFortStatType, Int>()
	@JvmField val homebaseNodeAttributes = mutableMapOf<String, Float>()
	private var accountInventoryBonus = 0
	private var worldInventoryBonus = 0

	init {
		api.eventBus.register(this)
	}

	@Subscribe
	@Synchronized
	fun updated(event: ProfileUpdatedEvent) {
		val profile = event.profileObj
		if (profile == null || profile.profileId != "campaign" || profile.accountId != accountId) {
			return
		}
		squads.values.forEach { squad ->
			squad.slots.forEach {
				it.item = null
				it.unlocked = false
			}
		}
		bonuses.clear()
		phoenixBonuses.clear()
		homebaseNodeAttributes.clear()
		for (item in profile.items.values) {
			when (item.primaryAssetType) {
				"Stat" -> {
					val primaryAssetName = item.primaryAssetName
					if (primaryAssetName.endsWith("_phoenix")) {
						Utils.sumKV(phoenixBonuses, EFortStatType.from(primaryAssetName.substring(0, primaryAssetName.length - "_phoenix".length)), item.quantity)
					} else {
						Utils.sumKV(bonuses, EFortStatType.from(primaryAssetName), item.quantity)
					}
				}
				"Hero", "Worker" -> {
					val squadId = item.attributes["squad_id"]?.asString ?: continue
					val squadSlotIdx = item.attributes["squad_slot_idx"]?.asInt ?: continue
					val squad = squads[squadId.toLowerCase(Locale.ROOT)] ?: continue
					squad.slots[squadSlotIdx].item = item
				}
				"HomebaseNode" -> {
					val defData = item.defData as? FortHomebaseNodeItemDefinition ?: continue
					repeat(item.quantity) {
						val level = defData.LevelData.getOrNull(it) ?: return@repeat
						for (rowName in level.GameplayEffectRowNames) {
							val row = homebaseNodeGameplayEffectDataTable.findRow(rowName) ?: continue
							val operation = row.get<HomebaseNodeGameplayEffectDataTableRow.EGameplayModOp>("Operation")
							if (operation != HomebaseNodeGameplayEffectDataTableRow.EGameplayModOp.Additive) {
								continue
							}
							val attributeName = row.get<FStructFallback>("Attribute").get<String>("AttributeName")
							val magnitude = row.get<Float>("Magnitude")
							homebaseNodeAttributes[attributeName] = (homebaseNodeAttributes[attributeName] ?: 0.0f) + magnitude
						}
						for (slotId in level.UnlockedSquadSlots) {
							val squad = squads[slotId.SquadId.text.toLowerCase(Locale.ROOT)] ?: continue
							val squadSlot = if (squad.slots.indices.contains(slotId.SquadSlotIndex)) squad.slots[slotId.SquadSlotIndex] else continue
							squadSlot.unlocked = true
						}
					}
				}
				"Token" -> when (item.primaryAssetName) {
					"accountinventorybonus" -> accountInventoryBonus = item.quantity
					"worldinventorybonus" -> worldInventoryBonus = item.quantity
				}
			}
		}
		squads.values.forEach { it.computeBonuses(bonuses) }
	}

	fun getStatBonus(stat: EFortStatType) = bonuses[stat] ?: 0

	fun calcEnergyByFORT(phoenix: Boolean = false): Float {
		val calculatedFortPoints = (if (phoenix) phoenixBonuses else bonuses).values.sum()
		val fortAttributeToPowerMultiplier = 4f
		return powerPointToRatingConversion?.eval(calculatedFortPoints * fortAttributeToPowerMultiplier) ?: 0f
	}

	fun getAccountInventorySize(): Int {
		return (homebaseNodeAttributes["InventorySizeBonus"]?.toInt() ?: 0) + accountInventoryBonus
	}

	fun getWorldInventorySize(): Int {
		return (homebaseNodeAttributes["WorldInventorySizeBonus"]?.toInt() ?: 0) + worldInventoryBonus
	}

	fun getStorageInventorySize() = homebaseNodeAttributes["StorageInventorySizeBonus"]?.toInt() ?: 0

	class Squad(@JvmField val squadId: String, @JvmField val backing: HomebaseSquad) {
		@JvmField val slots = Array(backing.CrewSlots.size) { SquadSlot(backing.CrewSlots[it], it) }
		@JvmField val bonuses = mutableMapOf<EFortStatType, Int>()
		val leadSlot by lazy { getSlotsByType(ESquadSlotType.SurvivorSquadLeadSurvivor).firstOrNull() }
		@JvmField var managerSynergy: String? = null
		@JvmField var managerPersonality: String? = null
		@JvmField var managerMatch = false
		@JvmField var matchingPersonalityBonus = 0
		@JvmField var mismatchingPersonalityPenalty = 0

		fun getSlotsByType(type: ESquadSlotType) = slots.filter { it.backing.SlotType == type }

		fun computeBonuses(totalBonuses: MutableMap<EFortStatType, Int>?) {
			if (backing.SquadType != EFortHomebaseSquadType.AttributeSquad) {
				return
			}
			val leadItem = leadSlot?.item
			if (leadItem != null) {
				managerSynergy = leadItem.attributes.getString("managerSynergy")
				managerPersonality = leadItem.attributes.getString("personality")
				managerMatch = managerSynergy.equals(backing.ManagerSynergyTag.toString(), true)
				(leadItem.defData as? FortWorkerType)?.apply {
					matchingPersonalityBonus = MatchingPersonalityBonus ?: 0
					mismatchingPersonalityPenalty = MismatchingPersonalityPenalty ?: 0
				}
			} else {
				managerSynergy = null
				managerPersonality = null
				managerMatch = false
				matchingPersonalityBonus = 0
				mismatchingPersonalityPenalty = 0
			}
			bonuses.clear()
			slots.forEach { it.computeBonuses(this, totalBonuses) }
		}

		fun getStatBonus(stat: EFortStatType) = bonuses[stat] ?: 0
	}

	class SquadSlot(@JvmField val backing: HomebaseSquadSlot, @JvmField val index: Int) {
		@JvmField var item: FortItemStack? = null
		@JvmField var unlocked = false
		@JvmField val bonuses = mutableMapOf<EFortStatType, Int>()
		@JvmField var personality: String? = null
		@JvmField var setBonus: String? = null
		@JvmField var personalityMatch = false

		fun computeBonuses(squad: Squad, totalBonuses: MutableMap<EFortStatType, Int>?) {
			bonuses.clear()
			val slotItem = item
			if (slotItem == null) {
				personality = null
				setBonus = null
				personalityMatch = false
				return
			}
			personality = slotItem.attributes.getString("personality")
			if (backing.SlotType == ESquadSlotType.SurvivorSquadSurvivor) {
				setBonus = slotItem.attributes.getString("set_bonus")
				personalityMatch = squad.managerPersonality != null && personality == squad.managerPersonality
			} else {
				setBonus = null
				personalityMatch = false
			}
			var powerLevel = slotItem.powerLevel
			if (backing.SlotType == ESquadSlotType.SurvivorSquadSurvivor && squad.managerPersonality != null) {
				powerLevel += if (personalityMatch) {
					squad.matchingPersonalityBonus
				} else {
					squad.mismatchingPersonalityPenalty
				}
			} else if (backing.SlotType == ESquadSlotType.SurvivorSquadLeadSurvivor && squad.managerMatch) {
				powerLevel *= 2f
			}
			for (slottingBonus in backing.SlottingBonuses) {
				val statType = EFortStatType.from(slottingBonus.AttributeGranted.AttributeName)
				val statBonus = slottingBonus.BonusCurve.eval(powerLevel).toInt()
				bonuses[statType] = statBonus
				Utils.sumKV(squad.bonuses, statType, statBonus)
				if (totalBonuses != null) Utils.sumKV(totalBonuses, statType, statBonus)
			}
		}

		fun getTotalBonus() = bonuses.values.sum()
	}
}