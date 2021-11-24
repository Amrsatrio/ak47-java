package com.tb24.discordbot.util

import com.tb24.discordbot.DiscordBot
import com.tb24.fn.model.McpVariantReader
import me.fungames.jfortniteparse.fort.exports.variants.*
import me.fungames.jfortniteparse.fort.objects.variants.BaseVariantDef
import me.fungames.jfortniteparse.ue4.objects.core.math.FLinearColor
import me.fungames.jfortniteparse.ue4.reader.FByteArchive
import me.fungames.jfortniteparse.util.parseHexBinary

val FortCosmeticVariant.backendChannelName get() = VariantChannelTag.toString().substringAfter("Cosmetics.Variant.Channel.")

fun FortCosmeticVariantBackedByArray.getActive(backendVariant: McpVariantReader?): BaseVariantDef? {
	var currentVariant: BaseVariantDef? = null
	val variants = this.variants
	if (variants != null) {
		var defaultVariant: BaseVariantDef? = null
		for (variant in variants) {
			if (backendVariant != null && variant.backendVariantName == backendVariant.active) {
				currentVariant = variant
				break
			} else if (variant.bIsDefault) {
				defaultVariant = variant
			}
		}
		if (currentVariant == null) {
			currentVariant = defaultVariant
		}
	}
	return currentVariant
}

fun FortCosmeticFloatSliderVariant.getActive(backendVariant: McpVariantReader?): Float {
	var number = DefaultStartingValue
	val activeValue = backendVariant?.active
	if (activeValue != null) {
		try {
			number = activeValue.substringAfter("FloatSlider.").toFloat()
		} catch (e: NumberFormatException) {
			DiscordBot.LOGGER.warn("Couldn't parse Numerical variant data: $backendVariant", e)
		}
	}
	return number
}

fun FortCosmeticNumericalVariant.getActive(backendVariant: McpVariantReader?): Int {
	var number = DefaultStartingNumeric
	val activeValue = backendVariant?.active
	if (activeValue != null) {
		try {
			number = activeValue.substringAfter("Numeric.").toInt()
		} catch (e: NumberFormatException) {
			DiscordBot.LOGGER.warn("Couldn't parse Numerical variant data: $backendVariant", e)
		}
	}
	return number
}

fun FortCosmeticRichColorVariant.getActive(backendVariant: McpVariantReader?): FLinearColor {
	var color = InlineVariant.RichColorVar.DefaultStartingColor
	val activeValue = backendVariant?.active
	if (activeValue != null) {
		try {
			color = FLinearColor(FByteArchive(activeValue.substringAfter("RichColor.").parseHexBinary()).apply { littleEndian = false })
		} catch (e: Exception) {
			DiscordBot.LOGGER.warn("Couldn't parse RichColor variant data: $backendVariant", e)
		}
	}
	return color
}