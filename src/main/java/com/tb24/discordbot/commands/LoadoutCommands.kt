package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.EAthenaCustomizationCategory.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.attributes.ILoadoutData
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.CosmeticLockerAttributes
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.getStringOr
import net.dv8tion.jda.api.EmbedBuilder

class AthenaLoadoutsCommand : BrigadierCommand("presets", "Shows your BR locker presets.", arrayOf("loadouts", "brpresets", "brloadouts")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { summary(it.source, "athena") }
		.then(argument("preset #", integer())
			.executes { details(it.source, "athena", getInteger(it, "preset #")) }
		)
}

class CampaignLoadoutsCommand : BrigadierCommand("stwpresets", "Shows your STW locker presets.", arrayOf("stwloadouts")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { summary(it.source, "campaign") }
		.then(argument("preset #", integer())
			.executes { details(it.source, "campaign", getInteger(it, "preset #")) }
		)
}

private fun summary(source: CommandSourceStack, profileId: String): Int {
	source.ensureSession()
	source.loading("Getting presets")
	source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
	val profile = source.api.profileManager.getProfileData(profileId)
	val attrs = profile.stats.attributes as ILoadoutData
	val mainLoadoutItem = attrs.loadouts.getOrNull(attrs.activeLoadoutIndex)?.let { profile.items[it] }
		?: throw SimpleCommandExceptionType(LiteralMessage("Main preset not found. Must be a bug.")).create()
	val embed = source.createEmbed()
		.setTitle(if (profileId == "athena") "Current BR locker" else "Current STW locker")
		.populateLoadoutContents(mainLoadoutItem.getAttributes(CosmeticLockerAttributes::class.java), profile)
	val loadoutLines = mutableListOf<String>()
	for (i in 1 until attrs.loadouts.size) {
		val loadoutId = attrs.loadouts[i] ?: continue
		val loadoutItem = profile.items[loadoutId] ?: continue
		val lockerName = loadoutItem.attributes.getStringOr("locker_name", "")
		loadoutLines.add("#%,d: %s".format(i, if (lockerName.isNotEmpty()) lockerName else "Unnamed Preset"))
	}
	if (loadoutLines.isNotEmpty()) {
		embed.addField("Your saved presets", loadoutLines.joinToString("\n"), false)
	}
	source.complete(null, embed.build())
	return Command.SINGLE_SUCCESS
}

private fun details(source: CommandSourceStack, profileId: String, index: Int): Int {
	source.ensureSession()
	source.loading("Getting presets")
	source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
	val profile = source.api.profileManager.getProfileData(profileId)
	val attrs = profile.stats.attributes as ILoadoutData
	val loadoutItem = (if (index > 0) attrs.loadouts.getOrNull(index)?.let { profile.items[it] } else null)
		?: throw SimpleCommandExceptionType(LiteralMessage("No preset found with number ${Formatters.num.format(index)}.")).create()
	val loadoutAttrs = loadoutItem.getAttributes(CosmeticLockerAttributes::class.java)
	source.complete(null, source.createEmbed()
		.setTitle("#%,d: %s".format(index, if (loadoutAttrs.locker_name.isNullOrEmpty()) "Unnamed Preset" else loadoutAttrs.locker_name))
		.populateLoadoutContents(loadoutAttrs, profile)
		.build())
	return Command.SINGLE_SUCCESS
}

private fun EmbedBuilder.populateLoadoutContents(loadoutAttrs: CosmeticLockerAttributes, profile: McpProfile): EmbedBuilder {
	val categories = if (profile.profileId == "athena") {
		arrayOf(Character, Backpack, Pickaxe, Glider, SkyDiveContrail, Dance, ItemWrap, MusicPack, LoadingScreen)
	} else {
		arrayOf(Character, Backpack, Pickaxe, Dance, ItemWrap, MusicPack, LoadingScreen)
	}
	for (type in categories) {
		val items = loadoutAttrs.locker_slots_data.getSlotItems(type)
		addField(type.name, items.joinToString(" - ") {
			val item = when {
				it.isNullOrEmpty() -> null
				it.contains(':') -> FortItemStack(it, 1)
				else -> profile.items[it]
			}
			item?.displayName ?: "Empty"
		}, items.size <= 1)
	}
	return this
}
