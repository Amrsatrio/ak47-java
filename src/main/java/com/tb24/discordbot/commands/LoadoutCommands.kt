package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.EAthenaCustomizationCategory.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.attributes.AthenaProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.CosmeticLockerAttributes

class AthenaLoadoutsCommand : BrigadierCommand("presets", "Shows your BR presets.", arrayOf("loadouts")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { details(it.source, "athena") }
}

class CampaignLoadoutsCommand : BrigadierCommand("stwpresets", "Shows your STW presets.", arrayOf("stwloadouts")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { details(it.source, "campaign") }
}

private fun details(source: CommandSourceStack, profileId: String): Int {
	source.ensureSession()
	source.loading("Getting presets")
	source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
	val profile = source.api.profileManager.getProfileData(profileId)
	val attrs = profile.stats.attributes as AthenaProfileAttributes
	val loadoutAttrs = attrs.loadouts.getOrNull(attrs.active_loadout_index)?.let { profile.items[it] }?.getAttributes(CosmeticLockerAttributes::class.java)
		?: throw SimpleCommandExceptionType(LiteralMessage("Preset not found. A bug maybe?")).create()
	val embed = source.createEmbed().setTitle("Presets / #%,d: %s".format(attrs.active_loadout_index, loadoutAttrs.locker_name ?: "Unnamed Preset"))
	for (type in arrayOf(Character, Backpack, Pickaxe, Glider, SkyDiveContrail, Dance, ItemWrap, MusicPack, LoadingScreen)) {
		val items = loadoutAttrs.locker_slots_data.getSlotItems(type)
		embed.addField(type.name, items.joinToString(" - ") {
			val item = when {
				it.isNullOrEmpty() -> null
				it.contains(':') -> FortItemStack(it, 1)
				else -> profile.items[it]
			}
			item?.displayName ?: "Empty"
		}, items.size <= 1)
	}
	source.complete(null, embed.build())
	return Command.SINGLE_SUCCESS
}
