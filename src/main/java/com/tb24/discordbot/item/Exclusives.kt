package com.tb24.discordbot.item

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.util.readString0
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.McpVariantReader
import com.tb24.fn.model.mcpprofile.McpProfile
import me.fungames.jfortniteparse.fort.exports.FortVariantTokenType
import okhttp3.Request
import java.util.*

enum class ExclusivesType {
	EXCLUSIVE, UNIQUE, RARE
}

class ExclusivesEntry(val templateId: String, val type: ExclusivesType, val reason: String)

val exclusives by lazy {
	val exclusivesCsvUrl = BotConfig.get().exclusivesCsvUrl
	if (exclusivesCsvUrl.isNullOrEmpty()) {
		throw SimpleCommandExceptionType(LiteralMessage("Exclusives data is not defined.")).create()
	}
	val response = DiscordBot.instance.okHttpClient.newCall(Request.Builder().url(exclusivesCsvUrl).build()).execute()
	if (!response.isSuccessful) {
		throw SimpleCommandExceptionType(LiteralMessage("Request failed with status code " + response.code)).create()
	}
	val lines = response.body!!.charStream().use { it.readLines() }
	val result = hashMapOf<String, ExclusivesEntry>()
	val terminator = hashSetOf(',')
	lines.forEachIndexed { index, line ->
		if (index != 0) {
			val reader = StringReader(line)
			val templateId = reader.readString0(terminator); reader.expect(',')
			val typeInitial = reader.readString0(terminator); reader.expect(',')
			val reason = reader.readString0(terminator)
			val type = ExclusivesType.values().first { it.name.startsWith(typeInitial, true) }
			result[templateId.toLowerCase()] = ExclusivesEntry(templateId, type, reason)
		}
	}
	result
}

fun getExclusiveItems(profiles: Collection<McpProfile>, types: EnumSet<ExclusivesType>, onlyCosmetics: Boolean): List<FortItemStack> {
	val localExclusives = exclusives.values
	val myExclusives = mutableListOf<FortItemStack>()

	// Cosmetic Variant Tokens need special treatment
	val athena = profiles.first { it.profileId == "athena" }
	for (e in localExclusives) {
		if (!e.templateId.startsWith("CosmeticVariantToken:") || e.type !in types) {
			continue
		}
		val variantTokenItem = FortItemStack(e.templateId, 1)
		val variantTokenDef = variantTokenItem.defData as? FortVariantTokenType ?: continue
		val cosmeticItemName = variantTokenDef.cosmetic_item.name.toString().toLowerCase()
		val item = athena.items.values.firstOrNull { it.primaryAssetName == cosmeticItemName } ?: continue
		val itemVariants = EpicApi.GSON.fromJson(item.attributes.getAsJsonArray("variants"), Array<McpVariantReader>::class.java)
		if (itemVariants.any { it.channel == variantTokenDef.VariantChanelTag.toString().substringAfterLast('.') && variantTokenDef.VariantNameTag.toString().substringAfterLast('.') in it.owned }) {
			myExclusives.add(variantTokenItem)
		}
	}

	return profiles.flatMapTo(myExclusives) { profile: McpProfile ->
		profile.items.values.filter { item -> item.primaryAssetType != "CosmeticVariantToken" && localExclusives.any { it.templateId.equals(item.templateId, true) && it.type in types } && (!onlyCosmetics || item.primaryAssetType != "HomebaseBannerIcon") }
	}
}