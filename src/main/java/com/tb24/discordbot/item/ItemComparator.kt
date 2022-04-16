package com.tb24.discordbot.item

import com.tb24.fn.model.FortItemStack

object ItemComparator : Comparator<FortItemStack> {
	override fun compare(a: FortItemStack, b: FortItemStack): Int {
		val aRating = a.powerLevel
		val bRating = b.powerLevel
		if (aRating != bRating) {
			return bRating.compareTo(aRating)
		}
		val aRarity = a.rarity
		val bRarity = b.rarity
		if (aRarity != bRarity) {
			return bRarity.compareTo(aRarity)
		}
		val aTier = ItemUtils.getTier(a.defData)
		val bTier = ItemUtils.getTier(b.defData)
		if (aTier != bTier) {
			return aTier - bTier
		}
		val aDisplayName = a.displayName
		val bDisplayName = b.displayName
		if (aDisplayName != bDisplayName) {
			return aDisplayName.compareTo(bDisplayName)
		}
		return a.quantity - b.quantity
	}
}