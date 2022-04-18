package com.tb24.discordbot.item

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.util.readString0
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
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
	return profiles.flatMap { profile ->
		profile.items.values.filter { item -> localExclusives.any { it.templateId.equals(item.templateId, true) && types.contains(it.type) } && (!onlyCosmetics || item.primaryAssetType != "CosmeticVariantToken" && item.primaryAssetType != "HomebaseBannerIcon") }
	}
}