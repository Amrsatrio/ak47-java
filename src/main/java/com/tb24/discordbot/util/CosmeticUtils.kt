package com.tb24.discordbot.util

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.DiscordBot
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.McpVariantReader
import com.tb24.fn.model.assetdata.CustomDynamicColorSwatch.ColorSwatchPair
import me.fungames.jfortniteparse.fort.exports.variants.*
import me.fungames.jfortniteparse.fort.objects.variants.BaseVariantDef
import me.fungames.jfortniteparse.ue4.objects.core.math.FLinearColor
import me.fungames.jfortniteparse.ue4.reader.FByteArchive
import me.fungames.jfortniteparse.util.parseHexBinary
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import java.util.*

fun parseCosmeticType(type: String): String {
	val lowerType = type.toLowerCase(Locale.ROOT)
	val filterType = when (if (lowerType.length > 2 && lowerType.endsWith('s')) lowerType.substring(0, lowerType.length - 1) else lowerType) {
		"o", "s", "character", "outfit", "skin" -> "AthenaCharacter"
		"b", "backpack", "backbling" -> "AthenaBackpack"
		"p", "pickaxe", "harvestingtool" -> "AthenaPickaxe"
		"g", "glider" -> "AthenaGlider"
		"c", "skydivecontrail", "contrail" -> "AthenaSkyDiveContrail"
		"d", "dance", "emote" -> "AthenaDance:AthenaDanceItemDefinition"
		"e", "emoticon" -> "AthenaDance:AthenaEmojiItemDefinition"
		"sp", "spray" -> "AthenaDance:AthenaSprayItemDefinition"
		"t", "toy" -> "AthenaDance:AthenaToyItemDefinition"
		"w", "itemwrap", "wrap" -> "AthenaItemWrap"
		"m", "musicpack", "music" -> "AthenaMusicPack"
		"l", "ls", "loadingscreen" -> "AthenaLoadingScreen"
		else -> throw SimpleCommandExceptionType(LiteralMessage("Unknown cosmetic type $type. Valid values are: (case insensitive)```\nOutfit, BackBling, HarvestingTool, Glider, Contrail, Emote, Spray, Emoticon, Toy, Wrap, Music, LoadingScreen\n```")).create()
	}
	return filterType
}

val COSMETIC_TYPE_CHOICES = arrayOf(
	Choice("Outfit", "AthenaCharacter"),
	Choice("Back Bling", "AthenaBackpack"),
	Choice("Harvesting Tool", "AthenaPickaxe"),
	Choice("Glider", "AthenaGlider"),
	Choice("Contrail", "AthenaSkyDiveContrail"),
	Choice("Dance", "AthenaDance:AthenaDanceItemDefinition"),
	Choice("Emoticon", "AthenaDance:AthenaEmojiItemDefinition"),
	Choice("Spray", "AthenaDance:AthenaSprayItemDefinition"),
	Choice("Toy", "AthenaDance:AthenaToyItemDefinition"),
	Choice("Wrap", "AthenaItemWrap"),
	Choice("Music", "AthenaMusicPack"),
	Choice("Loading Screen", "AthenaLoadingScreen")
)

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

fun FortCosmeticItemTexture.getActive(backendVariant: McpVariantReader?): FortItemStack {
	var item = ItemTextureVar.InnerDef.DefaultSelectedItem
	val activeValue = backendVariant?.active
	if (activeValue != null) {
		try {
			item = activeValue.substringAfter("ItemTexture.")
		} catch (e: Exception) {
			DiscordBot.LOGGER.warn("Couldn't parse ItemTexture variant data: $backendVariant", e)
		}
	}
	return FortItemStack(item, 1)
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

fun List<ColorSwatchPair>.findPair(color: FLinearColor) =
	firstOrNull { it.ColorValue.run { r == color.r && g == color.g && b == color.b && a == color.a } }