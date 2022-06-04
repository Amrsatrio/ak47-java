package com.tb24.discordbot.ui

import com.tb24.discordbot.util.getAdditionalDataOfType
import com.tb24.discordbot.util.purchasedBpOffers
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.*
import com.tb24.fn.model.assetdata.AthenaSeasonItemData_BattleStar.AthenaSeasonPageGrid
import com.tb24.fn.model.assetdata.AthenaSeasonItemData_CustomSkin.AthenaSeasonItemCustomSkinCategoryData
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats.BattlePassOfferPurchaseRecord
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText

class BattlePassViewController(val athena: McpProfile) {
	val stats = athena.stats as AthenaProfileStats
	val purchasedBpOffers = stats.purchasedBpOffers
	val seasonData = FortItemStack("AthenaSeason:athenaseason${stats.season_num}", 1).defData as? AthenaSeasonItemDefinition
	val battleStarData = seasonData?.getAdditionalDataOfType<AthenaSeasonItemData_BattleStar>()
	val customSkinData = seasonData?.getAdditionalDataOfType<AthenaSeasonItemData_CustomSkin>()

	val rewards by lazy { battleStarData?.PageList?.let(::Type) }
	val quests by lazy { battleStarData?.QuestPageList?.let(::Type) }
	val bonuses by lazy { battleStarData?.BonusPageList?.let(::Type) }
	val customization by lazy { battleStarData?.CustomizationPageList?.let(::Type) ?: customSkinData?.let(::Type) }

	inner class Type {
		val sections: List<Section>
		internal var currentIndex = 0
		var purchased = 0

		constructor(pages: List<AthenaSeasonPageGrid>) {
			sections = pages.map { Page(it, this) }
		}

		constructor(customSkinData: AthenaSeasonItemData_CustomSkin) {
			sections = customSkinData.Categories.map { Category(it, this) }
		}

		val pages get() = sections.asSequence().map { it as Page }
		val categories get() = sections.asSequence().map { it as Category }
	}

	abstract inner class Section(entries: List<Lazy<AthenaSeasonItemEntryBase>>, val parent: Type) {
		val entries = entries.map { it ->
			val entry = Entry(it.value, this)
			entry.purchaseRecord = (entry.backing as? AthenaSeasonItemEntryOfferBase)?.BattlePassOffer?.OfferId?.let { purchasedBpOffers[it] }
			if (entry.purchaseRecord != null) {
				++purchased
				++parent.purchased
			}
			entry
		}
		var purchased = 0
		abstract val title: FText
		open val isUnlocked get() = parent.purchased >= rewardsForUnlock
		abstract val rewardsForUnlock: Int
	}

	inner class Page(val backing: AthenaSeasonPageGrid, parent: Type) : Section(backing.RewardEntryList, parent) {
		override val title get() = backing.CustomGridName!!
		override val isUnlocked get() = super.isUnlocked || stats.level >= backing.LevelsNeededForUnlock
		override val rewardsForUnlock = backing.RewardsNeededForUnlock
	}

	inner class Category(val backing: AthenaSeasonItemCustomSkinCategoryData, parent: Type) : Section(backing.Entries, parent) {
		override val title get() = backing.Name!!
		override val rewardsForUnlock = backing.RequiredRewardsToUnlock
	}

	inner class Entry(val backing: AthenaSeasonItemEntryBase, val parent: Section) {
		val index = parent.parent.currentIndex++
		var purchaseRecord: BattlePassOfferPurchaseRecord? = null
	}
}