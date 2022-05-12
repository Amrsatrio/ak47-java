package com.tb24.discordbot.ui

import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.QuestCategoryData
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.fn.util.getInt
import com.tb24.fn.util.getString
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleItemDefinition.FortChallengeBundleQuestEntry
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition.EFortQuestSubtype
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTag
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTagContainer
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import java.util.*

class QuestsViewController(athena: McpProfile, knownCategories: List<QuestCategoryData> = getCategories()) {
	val allCategories: Map<String, QuestCategory>
	val categories: List<QuestCategory>
	private val questTagsCache = hashMapOf<String, FGameplayTagContainer>()
	private val allBundles = hashMapOf<String, FortItemStack>()

	init {
		// Gather quests
		val quests = mutableListOf<FortItemStack>()
		for (item in athena.items.values) {
			if (item.primaryAssetType == "Quest") {
				val defData = item.defData
				if (defData !is FortQuestItemDefinition || defData.bHidden == true || item.attributes["quest_state"]?.asString != "Active") {
					continue
				}
				quests.add(item)
			} else if (item.primaryAssetType == "ChallengeBundle") {
				allBundles[item.primaryAssetName] = item
			}
		}

		// Build categories
		val localAllCategories = hashMapOf<String /*category object name*/, QuestCategory>()
		for (categoryData in knownCategories) {
			localAllCategories.getOrPut(categoryData.name) { QuestCategory(categoryData, athena) }.addQuests(quests)
		}
		questTagsCache.clear()
		allBundles.clear()
		categories = localAllCategories.values.filter { (it.backing.bAlwaysDisplay || it.headers.isNotEmpty()) && it.hasRequiredItem(athena) }.sortedWith { a, b ->
			val sortOrderCmp = (a.backing.SortOrder ?: Int.MAX_VALUE) - (b.backing.SortOrder ?: Int.MAX_VALUE)
			if (sortOrderCmp != 0) {
				return@sortedWith sortOrderCmp
			}
			a.backing.name.compareTo(b.backing.name)
		}
		allCategories = localAllCategories
	}

	companion object {
		fun getCategories() = AssetManager.INSTANCE.assetRegistry.templateIdToAssetDataMap.values.filter { it.assetClass == "QuestCategoryData" }.map { loadObject<QuestCategoryData>(it.objectPath)!! }
	}

	inner class QuestCategory(val backing: QuestCategoryData, private val athena: McpProfile) {
		val goalCards = hashMapOf<String, GoalCard>()
		private val allHeaders = mutableListOf<QuestCategoryHeader>()
		private val defaultHeader = QuestCategoryHeader(this, backing.DefaultHeaderName, backing.DefaultHeaderSortOrder ?: 0) // TODO Is it 0 or something else?
		private val challengeBundles = hashMapOf<String, FortItemStack?>()

		init {
			allHeaders.add(defaultHeader)
			backing.AdditionalHeaders?.mapTo(allHeaders) { QuestCategoryHeader(this, it) }
			allHeaders.sortBy { it.sortOrder }
		}

		fun addQuests(quests: List<FortItemStack>) {
			for (quest in quests) {
				// Determine if quest should be included in this category
				val questDef = quest.defData as FortQuestItemDefinition
				val cbId = quest.attributes.getString("challenge_bundle_id")!!
				val tags = questTagsCache.getOrPut(quest.primaryAssetName) {
					val mergedTags = mutableListOf<FName>()
					if (cbId.isNotEmpty()) {
						athena.items[cbId]!!.defData.GameplayTags?.let(mergedTags::addAll)
					}
					quest.defData.GameplayTags?.let(mergedTags::addAll)
					FGameplayTagContainer(mergedTags)
				}
				val shouldIncludeByIncludeTags = backing.IncludeTags == null || backing.IncludeTags.any { tags.getValue(it.toString()) != null }
				val shouldIncludeByExcludeTags = backing.ExcludeTags == null || backing.ExcludeTags.none { tags.getValue(it.toString()) != null }
				if (!shouldIncludeByIncludeTags || !shouldIncludeByExcludeTags) {
					continue
				}

				// Register the ChallengeBundle associated with this quest
				val cb = if (cbId.isNotEmpty()) challengeBundles.getOrPut(cbId) {
					val newCb = athena.items[cbId]!!
					val newCbDef = newCb.defData as FortChallengeBundleItemDefinition
					newCbDef.HideUntilBundleCompleted?.let {
						val theCb = allBundles[it.toString().substringAfterLast('.').toLowerCase()] ?: return@getOrPut null
						if (theCb.attributes.getInt("num_quests_completed") < (theCb.defData as FortChallengeBundleItemDefinition).QuestInfos.size) {
							return@getOrPut null
						}
					}
					onNewChallengeBundle(newCb)
					newCb
				} ?: continue else null
				val cbDef = cb?.defData as? FortChallengeBundleItemDefinition

				// Is this a normal quest or a bonus goal quest?
				if (cbDef != null && cbDef.bSkipAddToGoalBundles != true && cbDef.GoalCardDisplayData != null) {
					// Bonus goal quest
					goalCards[cb.itemId]!!.addQuest(quest)
				} else if (questDef.QuestSubtype == EFortQuestSubtype.PunchCard) {
					goalCards.getOrPut("daily") { GoalCard() }.addQuest(quest)
				} else {
					// Add quest to the subcategory (header) it belongs to
					val header = allHeaders.firstOrNull { tags.getValue(it.tag.toString()) != null } ?: defaultHeader
					header.addQuest(quest, cb)
				}
			}
		}

		private var _headers: List<QuestCategoryHeader>? = null
		val headers: List<QuestCategoryHeader>
			get() {
				if (_headers == null) {
					_headers = allHeaders.filter { it.quests.isNotEmpty() }
				}
				return _headers!!
			}

		private fun onNewChallengeBundle(cb: FortItemStack) {
			val cbDef = cb.defData as FortChallengeBundleItemDefinition
			if (cbDef.bSkipAddToGoalBundles == true) {
				return
			}
			cbDef.GoalCardDisplayData?.let {
				val goalCard = GoalCard(cb)
				goalCards[cb.itemId] = goalCard
			}
		}

		fun hasRequiredItem(athena: McpProfile): Boolean {
			val templateId = backing.RequiredProfileItemTemplateId ?: return true
			return athena.items.values.any { it.templateId == templateId }
		}
	}

	class GoalCard : Comparator<Quest> {
		private var cb: FortItemStack? = null
		val displayData get() = cb?.let { (it.defData as FortChallengeBundleItemDefinition).GoalCardDisplayData!! }
			?: loadObject("/Game/Athena/HUD/MiniMap/GoalsPage/GoalsPageQuestCard.Default__GoalsPageQuestCard_C")!!.get("DailyCardDisplayData")
		val subHeaderText get() = displayData.let {
			val tier = it.MilestoneTier ?: 0
			it.SubHeaderText?.format()
				?: (if (tier > 0 && tier < 10) {
					FText("", "48CB814E41A1276156D22784AE137546", "Complete all to advance to Tier {tier}").format()!!.replace("{tier}", Formatters.num.format(tier + 1))
				} else null)
		}
		private var indices: Map<String, Int>? = null
		val quests = TreeSet(this)

		constructor()

		constructor(cb: FortItemStack) {
			this.cb = cb
			val cbDef = cb.defData as FortChallengeBundleItemDefinition
			val localIndices = hashMapOf<String, Int>()
			cbDef.QuestInfos.forEachIndexed { i, questInfo ->
				val questName = questInfo.QuestDefinition.toString().substringAfterLast('.').toLowerCase()
				localIndices[questName] = i
			}
			indices = localIndices
		}

		fun addQuest(quest: FortItemStack) {
			quests.add(Quest(quest, cb))
		}

		override fun compare(a: Quest, b: Quest): Int {
			indices?.let {
				return it[a.quest.primaryAssetName]!! - it[b.quest.primaryAssetName]!!
			}
			return a.quest.primaryAssetName.compareTo(b.quest.primaryAssetName)
		}

		enum class Level(val min: Int, val medalTexture: String?) {
			NONE(0, null),
			BRONZE(1, "/Game/Athena/HUD/MiniMap/Art/T_UI_Medals_Bronze_128px.T_UI_Medals_Bronze_128px"),
			SILVER(4, "/Game/Athena/HUD/MiniMap/Art/T_UI_Medals_Silver_128px.T_UI_Medals_Silver_128px"),
			GOLD(7, "/Game/Athena/HUD/MiniMap/Art/T_UI_Medals_Gold_128px.T_UI_Medals_Gold_128px"),
			DIAMOND(10, "/Game/Athena/HUD/MiniMap/Art/T_UI_Medals_Diamond_128px.T_UI_Medals_Diamond_128px");

			companion object {
				fun fromTier(tier: Int) = values().reversed().first { it.min <= tier }
			}
		}
	}

	class QuestCategoryHeader(val owner: QuestCategory, val name: FText, val sortOrder: Int) {
		var tag: FGameplayTag? = null
		val quests = TreeSet<Quest> { a, b ->
			val sortPriorityCmp = ((a.quest.defData as FortQuestItemDefinition).SortPriority ?: 0) - ((b.quest.defData as FortQuestItemDefinition).SortPriority ?: 0)
			if (sortPriorityCmp != 0) {
				return@TreeSet sortPriorityCmp
			}
			a.quest.displayName.compareTo(b.quest.displayName)
		}
		private val challengeBundles = hashMapOf<String, FortItemStack>()
		private val inactiveQuestInfos = mutableMapOf<String, FortChallengeBundleQuestEntry>()

		constructor(owner: QuestCategory, backing: QuestCategoryData.QuestCategoryHeaderData) : this(owner, backing.HeaderName, backing.HeaderSortOrder) {
			tag = backing.HeaderTag
		}

		fun addQuest(quest: FortItemStack, cb: FortItemStack?) {
			if (cb != null && cb.itemId !in challengeBundles) {
				challengeBundles[cb.itemId] = cb
				onNewChallengeBundle(cb)
			}
			inactiveQuestInfos.remove(quest.primaryAssetName)
			quests.add(Quest(quest, cb))
		}

		private fun onNewChallengeBundle(cb: FortItemStack) {
			val cbDef = cb.defData as FortChallengeBundleItemDefinition
			cbDef.QuestInfos.associateByTo(inactiveQuestInfos) {
				it.QuestDefinition.toString().substringAfterLast('.').toLowerCase()
			}
		}
	}

	class Quest(val quest: FortItemStack, val challengeBundle: FortItemStack?)
}