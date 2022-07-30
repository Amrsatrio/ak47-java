package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.model.EAthenaCustomizationCategory.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.subgame.CopyCosmeticLoadout
import com.tb24.fn.model.mcpprofile.commands.subgame.SetCosmeticLockerName
import com.tb24.fn.model.mcpprofile.commands.subgame.SetRandomCosmeticLoadoutFlag
import com.tb24.fn.model.mcpprofile.item.FortCosmeticLockerItem
import com.tb24.fn.model.mcpprofile.stats.ILoadoutData
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.getString
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle

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
	while (true) {
		val profile = source.api.profileManager.getProfileData(profileId)
		val stats = profile.stats as ILoadoutData
		val mainLoadoutItem = FortCosmeticLockerItem.getFromProfile(profile, null)
			?: throw SimpleCommandExceptionType(LiteralMessage("Main preset not found. Must be a bug.")).create()
		val embed = source.createEmbed()
			.setTitle(if (profileId == "athena") "Current BR locker" else "Current STW locker")
			.populateLoadoutContents(mainLoadoutItem, profile)
		val buttons = mutableListOf<Button>()
		val lastAppliedLoadoutItem = stats.lastAppliedLoadout?.let { profile.items[it] }?.getAttributes(FortCosmeticLockerItem::class.java)
		val lastAppliedLoadoutIndex = stats.loadouts.indexOf(stats.lastAppliedLoadout)
		if (lastAppliedLoadoutItem != null) {
			if (mainLoadoutItem.isSameAs(lastAppliedLoadoutItem, mutableListOf())) {
				buttons.add(Button.of(ButtonStyle.SECONDARY, "saveToLastApplied", "Saved", Emoji.fromUnicode("✅")).asDisabled())
			} else {
				buttons.add(Button.secondary("saveToLastApplied", "Save to %s (#%,d)...".format(lastAppliedLoadoutItem.locker_name.ifEmpty { "Unnamed Preset" }, lastAppliedLoadoutIndex)))
			}
		}
		buttons.add(Button.secondary("saveTo", "Save to..."))
		buttons.add(Button.secondary("showPresets", "All presets (%,d)".format(stats.loadoutsCount)))
		val oldRandom = stats.isUsingRandomLoadout
		buttons.add(Button.secondary("toggleShuffle", if (oldRandom) "Disable shuffle" else "Enable shuffle"))
		val message = source.complete(null, embed.build(), ActionRow.of(buttons))
		source.loadingMsg = message
		when (message.awaitOneComponent(source, false).componentId) {
			"saveTo" -> {
				val allPresetsMsg = source.complete(null, source.createEmbed()
					.setTitle("Type the preset slot number to save to")
					.populateLoadoutsList(profile)
					.setFooter("Or type 'cancel' to cancel") // TODO Use button to cancel, will have to manage two active collectors at once
					.build())
				source.loadingMsg = allPresetsMsg
				val response = source.channel.awaitMessages({ collected, _, _ -> collected.author == source.author && (collected.contentRaw.equals("cancel", true) || collected.contentRaw.toIntOrNull() != null) }, AwaitMessagesOptions().apply {
					max = 1
					time = 60000L
					errors = arrayOf(CollectorEndReason.TIME)
				}).await().first().contentRaw
				if (response.equals("cancel", true)) {
					continue
				}
				val choice = response.toInt()
				if (choice <= 0 || choice > 100) {
					throw SimpleCommandExceptionType(LiteralMessage("The provided choice is not a valid preset slot number.")).create()
				}
				val preset = stats.loadouts.getOrNull(choice)?.let { profile.items[it] }?.getAttributes(FortCosmeticLockerItem::class.java)
				if (preset != null && mainLoadoutItem.isSameAs(preset, mutableListOf())) {
					val confirmationMsg = source.complete("Overwrite %s (#%,d) with current loadout?".format(preset.locker_name.ifEmpty { "Unnamed Preset" }, choice), null, confirmationButtons())
					source.loadingMsg = confirmationMsg
					if (!confirmationMsg.awaitConfirmation(source).await()) {
						continue
					}
				}
				source.api.profileManager.dispatchClientCommandRequest(CopyCosmeticLoadout().apply {
					sourceIndex = 0
					targetIndex = choice
					optNewNameForTarget = if (preset == null) "PRESET %,d".format(choice) else ""
				}, profileId).await()
			}
			"saveToLastApplied" -> {
				check(lastAppliedLoadoutItem != null)
				val confirmationMsg = source.complete("Overwrite your last loaded preset with current loadout?", null, confirmationButtons())
				source.loadingMsg = confirmationMsg
				if (!confirmationMsg.awaitConfirmation(source).await()) {
					continue
				}
				source.api.profileManager.dispatchClientCommandRequest(CopyCosmeticLoadout().apply {
					sourceIndex = 0
					targetIndex = lastAppliedLoadoutIndex
					optNewNameForTarget = ""
				}, profileId).await()
			}
			"showPresets" -> {
				val allPresetsMsg = source.complete(null, source.createEmbed()
					.setTitle("Presets")
					.populateLoadoutsList(profile)
					.build(), ActionRow.of(Button.secondary("back", "Back")))
				source.loadingMsg = allPresetsMsg
				val interaction = allPresetsMsg.awaitOneComponent(source, false)
				if (interaction.componentId == "back") {
					continue
				}
				break
			}
			"toggleShuffle" -> {
				source.api.profileManager.dispatchClientCommandRequest(SetRandomCosmeticLoadoutFlag().apply {
					random = !oldRandom
				}, profileId).await()
			}
			else -> break
		}
	}
	return Command.SINGLE_SUCCESS
}

private fun details(source: CommandSourceStack, profileId: String, index: Int): Int {
	source.ensureSession()
	source.loading("Getting presets")
	source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
	while (true) {
		val profile = source.api.profileManager.getProfileData(profileId)
		val stats = profile.stats as ILoadoutData
		val loadoutItem = (if (index > 0) stats.loadouts.getOrNull(index)?.let { profile.items[it] } else null)
			?: throw SimpleCommandExceptionType(LiteralMessage("No preset found with number ${Formatters.num.format(index)}.")).create()
		val loadoutAttrs = loadoutItem.getAttributes(FortCosmeticLockerItem::class.java)
		val buttons = mutableListOf<Button>()
		val mainLoadoutItem = FortCosmeticLockerItem.getFromProfile(profile, null)
			?: throw SimpleCommandExceptionType(LiteralMessage("Main preset not found. Must be a bug.")).create()
		if (loadoutAttrs.isSameAs(mainLoadoutItem, mutableListOf())) {
			buttons.add(Button.of(ButtonStyle.PRIMARY, "load", "In use", Emoji.fromUnicode("✅")).asDisabled())
		} else {
			buttons.add(Button.primary("load", "Load"))
		}
		buttons.add(Button.secondary("rename", "Rename"))
		val loadoutName = if (loadoutAttrs.locker_name.isNullOrEmpty()) "Unnamed Preset" else loadoutAttrs.locker_name
		val message = source.complete(null, source.createEmbed()
			.setTitle("#%,d: %s".format(index, loadoutName))
			.populateLoadoutContents(loadoutAttrs, profile)
			.build(), ActionRow.of(buttons))
		source.loadingMsg = message
		when (message.awaitOneComponent(source, false).componentId) {
			"load" -> {
				val confirmationMsg = source.complete("Overwrite your current loadout with %s?".format(loadoutName), null, confirmationButtons())
				source.loadingMsg = confirmationMsg
				if (!confirmationMsg.awaitConfirmation(source).await()) {
					continue
				}
				source.api.profileManager.dispatchClientCommandRequest(CopyCosmeticLoadout().apply {
					sourceIndex = index
					targetIndex = 0
					optNewNameForTarget = ""
				}, profileId).await()
			}
			"rename" -> {
				source.loadingMsg?.finalizeComponents(setOf("rename"))
				val promptMsg = source.channel.sendMessage("The current preset name is: `$loadoutName`\nEnter the new preset name: (⏱ 60s)").complete()
				val new = source.channel.awaitMessages({ collected, _, _ -> collected.author == source.author }, AwaitMessagesOptions().apply {
					max = 1
					time = 60000L
					errors = arrayOf(CollectorEndReason.TIME)
				}).await().first().contentRaw
				source.api.profileManager.dispatchClientCommandRequest(SetCosmeticLockerName().apply {
					lockerItem = loadoutItem.itemId
					name = new
				}, profileId).await()
				promptMsg.delete().queue()
			}
			else -> break
		}
	}
	return Command.SINGLE_SUCCESS
}

private fun EmbedBuilder.populateLoadoutsList(profile: McpProfile): EmbedBuilder {
	val stats = profile.stats as ILoadoutData
	val maxPresetsNum = 100
	val chunk = mutableListOf<String>()
	var chunkStart = 1

	fun finalizeChunk() {
		if (chunk.isEmpty()) return
		addField("%,d-%,d".format(chunkStart, chunkStart + chunk.size - 1), chunk.joinToString("\n"), true)
		chunkStart += chunk.size
		chunk.clear()
	}

	for (i in 1..maxPresetsNum) {
		val loadout = stats.loadouts.getOrNull(i)
		val loadoutName = if (loadout != null) profile.items[loadout]?.let { it.attributes.getString("locker_name", "").ifEmpty { "Unnamed Preset" } } else "\u2014"
		val s = "`%2d` %s".format(i, loadoutName)
		chunk.add(s)
		if (chunk.size == 10) {
			finalizeChunk()
		}
	}
	finalizeChunk()
	return this
}

private val names = mapOf(
	Character to "Outfit",
	Backpack to "Back Bling",
	Pickaxe to "Harvesting Tool",
	Glider to "Glider",
	SkyDiveContrail to "Contrail",
	Dance to "Emote",
	ItemWrap to "Wrap",
	MusicPack to "Music",
	LoadingScreen to "Loading Screen"
)

private val wrapAssignments = arrayOf("Vehicle", "Assault Rifle", "Shotgun", "SMG", "Sniper", "Pistol", "Misc", "???")

private fun EmbedBuilder.populateLoadoutContents(loadoutAttrs: FortCosmeticLockerItem, profile: McpProfile): EmbedBuilder {
	val categories = if (profile.profileId == "athena") {
		arrayOf(Character, Backpack, Pickaxe, Glider, SkyDiveContrail, Dance, ItemWrap, MusicPack, LoadingScreen)
	} else {
		arrayOf(Character, Backpack, Pickaxe, Dance, ItemWrap, MusicPack, LoadingScreen)
	}
	for (type in categories) {
		val items = loadoutAttrs.locker_slots_data.getSlotItems(type)
		var i = 0
		addField(names[type].orEmpty(), items.joinToString(if (type == ItemWrap) "\n" else " - ") {
			val item = when {
				it.isNullOrEmpty() -> null
				it.contains(':') -> FortItemStack(it, 1)
				else -> profile.items[it]
			}
			(if (type == ItemWrap) wrapAssignments.getOrNull(i++) + ": " else "") + (item?.displayName ?: "Empty")
		}, type != Dance)
	}
	return this
}
