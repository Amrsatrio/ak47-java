package com.tb24.discordbot.util

import com.google.common.collect.ComparisonChain
import com.tb24.discordbot.item.ExclusivesType
import com.tb24.discordbot.item.exclusives
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.format
import me.fungames.jfortniteparse.fort.enums.EFortRarity

class SimpleAthenaLockerItemComparator : Comparator<FortItemStack> {
	@JvmField var prioritizeFavorites = true
	@JvmField var prioritizeExclusives = false

	override fun compare(o1: FortItemStack, o2: FortItemStack): Int {
		val series1 = o1.defData?.Series?.value
		val series2 = o2.defData?.Series?.value
		var chain = ComparisonChain.start()
		if (prioritizeFavorites) {
			chain = chain.compareTrueFirst(o1.isFavorite, o2.isFavorite)
		}
		if (prioritizeExclusives) {
			val isExclusive1 = exclusives[o1.templateId.toLowerCase()]?.type == ExclusivesType.EXCLUSIVE
			val isExclusive2 = exclusives[o2.templateId.toLowerCase()]?.type == ExclusivesType.EXCLUSIVE
			chain = chain.compareTrueFirst(isExclusive1, isExclusive2)
		}
		chain = chain.compareTrueFirst(series1 != null, series2 != null)
		chain = chain.compare(
			if (series2 != null) EFortRarity.Common else o2.rarity,
			if (series1 != null) EFortRarity.Common else o1.rarity
		)
		chain = chain.compare(
			series1?.DisplayName?.format() ?: "",
			series2?.DisplayName?.format() ?: ""
		)
		chain = chain.compare(
			o1.transformedDefData?.DisplayName?.format() ?: o1.primaryAssetName,
			o2.transformedDefData?.DisplayName?.format() ?: o2.primaryAssetName
		)
		return chain.result()
	}
}