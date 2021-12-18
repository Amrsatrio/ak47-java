package com.tb24.discordbot.util

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.DiscordBot
import com.tb24.fn.model.McpVariantReader
import me.fungames.jfortniteparse.fort.exports.variants.*
import me.fungames.jfortniteparse.fort.objects.variants.BaseVariantDef
import me.fungames.jfortniteparse.ue4.objects.core.math.FLinearColor
import me.fungames.jfortniteparse.ue4.reader.FByteArchive
import me.fungames.jfortniteparse.util.parseHexBinary
import java.util.*

fun parseCosmeticType(type: String): String {
	val lowerType = type.toLowerCase(Locale.ROOT)
	val filterType = when (if (lowerType.endsWith('s')) lowerType.substring(0, lowerType.length - 1) else lowerType) {
		"character", "outfit", "skin" -> "AthenaCharacter"
		"backpack", "backbling" -> "AthenaBackpack"
		"pickaxe", "harvestingtool" -> "AthenaPickaxe"
		"glider" -> "AthenaGlider"
		"skydivecontrail", "contrail" -> "AthenaSkyDiveContrail"
		"dance", "emote" -> "AthenaDance:AthenaDanceItemDefinition"
		"spray" -> "AthenaDance:AthenaSprayItemDefinition"
		"emoticon" -> "AthenaDance:AthenaEmojiItemDefinition"
		"toy" -> "AthenaDance:AthenaToyItemDefinition"
		"itemwrap", "wrap" -> "AthenaItemWrap"
		"musicpack", "music" -> "AthenaMusicPack"
		"loadingscreen" -> "AthenaLoadingScreen"
		else -> throw SimpleCommandExceptionType(LiteralMessage("Unknown cosmetic type $type. Valid values are: (case insensitive)```\nOutfit, BackBling, HarvestingTool, Glider, Contrail, Emote, Spray, Emoticon, Toy, Wrap, Music, LoadingScreen\n```")).create()
	}
	return filterType
}

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