package com.tb24.discordbot.ui;

import com.tb24.discordbot.util.getAdditionalDataOfType
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.AthenaSeasonItemData_BattleStar
import com.tb24.fn.model.assetdata.AthenaSeasonItemData_BattleStar.AthenaSeasonPageGrid
import com.tb24.fn.model.assetdata.AthenaSeasonItemDefinition
import com.tb24.fn.model.assetdata.AthenaSeasonItemEntryBase
import com.tb24.fn.model.assetdata.AthenaSeasonItemEntryOfferBase
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats.BattlePassOfferPurchaseRecord

class BattlePassViewController(val athena: McpProfile) {
	val stats = athena.stats as AthenaProfileStats
	val purchasedBpOffers = stats.purchased_bp_offers?.associateBy { it.offerId } ?: emptyMap()
	val seasonData = FortItemStack("AthenaSeason:athenaseason${stats.season_num}", 1).defData as? AthenaSeasonItemDefinition ?: error("Season data not found.")
	val battleStarData = seasonData.getAdditionalDataOfType<AthenaSeasonItemData_BattleStar>()!!

	val rewards by lazy { Section(battleStarData.PageList) }
	val quests by lazy { Section(battleStarData.QuestPageList) }
	val bonuses by lazy { Section(battleStarData.BonusPageList) }

	inner class Section(inPages: List<AthenaSeasonPageGrid>) {
		val pages = inPages.map {
			val page = Page(it, this)
			page
		}
		internal var currentIndex = 0
		var purchased = 0
	}

	inner class Page(val backing: AthenaSeasonPageGrid, val section: Section) {
		val entries = backing.RewardEntryList.map { it ->
			val entry = Entry(it.value, this)
			entry.purchaseRecord = (entry.backing as? AthenaSeasonItemEntryOfferBase)?.BattlePassOffer?.OfferId?.let { purchasedBpOffers[it] }
			if (entry.purchaseRecord != null) {
				++purchased
				++section.purchased
			}
			entry
		}
		var purchased = 0
		val isUnlocked get() = stats.level >= backing.LevelsNeededForUnlock && section.purchased >= backing.RewardsNeededForUnlock
	}

	inner class Entry(val backing: AthenaSeasonItemEntryBase, val page: Page) {
		var index = page.section.currentIndex++
		var purchaseRecord: BattlePassOfferPurchaseRecord? = null
	}
}