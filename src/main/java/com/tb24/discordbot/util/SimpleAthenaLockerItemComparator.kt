package com.tb24.discordbot.util

import com.google.common.collect.ComparisonChain
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.format
import com.tb24.fn.util.getBooleanOr

class SimpleAthenaLockerItemComparator : Comparator<FortItemStack> {
	@JvmField var bPrioritizeFavorites = true

	override fun compare(o1: FortItemStack, o2: FortItemStack) = ComparisonChain.start()
		.run {
			if (bPrioritizeFavorites) {
				compareTrueFirst(
					o1.attributes.getBooleanOr("favorite", false),
					o2.attributes.getBooleanOr("favorite", false)
				)
			} else this
		}
		.compare(o2.rarity, o1.rarity).run {
			val series1 = o1.defData?.Series?.value
			val series2 = o2.defData?.Series?.value
			compare(
				series1?.DisplayName?.format() ?: "",
				series2?.DisplayName?.format() ?: ""
			)
		}
		.compare(
			o1.transformedDefData?.DisplayName?.format() ?: o1.primaryAssetName,
			o2.transformedDefData?.DisplayName?.format() ?: o2.primaryAssetName
		)
		.result()
}