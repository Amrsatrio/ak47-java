package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.FortOutpostItemDefinition
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.FortOutpostItem
import com.tb24.fn.util.Formatters
import java.util.*

class StormShieldCommand : BrigadierCommand("stormshield", "Shows info about your storm shields.", arrayOf("ss", "ssd")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Loading storm shields")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "metadata").await()
			val metadata = source.api.profileManager.getProfileData("metadata")
			val outposts = sortedMapOf<Int, FortItemStack>()
			for (item in metadata.items.values) {
				if (item.primaryAssetType == "Outpost") {
					outposts[(item.defData as FortOutpostItemDefinition).TheaterIndex] = item
				}
			}
			if (outposts.isEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage("You have no storm shields.")).create()
			}
			source.complete(null, source.createEmbed()
				.setTitle("Storm Shields")
				.setDescription(outposts.entries.joinToString("\n") { (_, item) ->
					val attrs = item.getAttributes(FortOutpostItem::class.java)
					val sb = StringBuilder(item.displayName).append(": Level ").append(Formatters.num.format(attrs.level))
					if (attrs.level >= 10) {
						sb.append(", Endurance Wave ").append(Formatters.num.format(attrs.outpost_core_info.highestEnduranceWaveReached))
					}
					sb.toString()
				})
				.setFooter("Use ${source.prefix}${c.commandName} <zone> for more details")
				.build())
			Command.SINGLE_SUCCESS
		}
		.then(argument("zone", word())
			.executes { c ->
				val source = c.source
				source.ensureSession()
				val lookupTemplateId = when (getString(c, "zone").toLowerCase(Locale.ROOT)) {
					"stonewood" -> "Outpost:outpostcore_pve_01"
					"plankerton" -> "Outpost:outpostcore_pve_02"
					"canny", "cannyvalley" -> "Outpost:outpostcore_pve_03"
					"twine", "twinepeaks" -> "Outpost:outpostcore_pve_04"
					else -> throw SimpleCommandExceptionType(LiteralMessage("Valid zones are: stonewood, plankerton, canny, twine")).create()
				}
				source.loading("Loading storm shield")
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "metadata").await()
				val metadata = source.api.profileManager.getProfileData("metadata")
				val item = metadata.items.values.firstOrNull { it.templateId == lookupTemplateId }
					?: throw SimpleCommandExceptionType(LiteralMessage("You don't have a " + FortItemStack(lookupTemplateId, 1).displayName)).create()
				val attrs = item.getAttributes(FortOutpostItem::class.java)
				val hasReachedMaxLevel = attrs.level >= 10
				val embed = source.createEmbed()
					.setTitle(item.displayName)
					.addField("Level", if (hasReachedMaxLevel) "%,d (Endurance unlocked!)".format(attrs.level) else Formatters.num.format(attrs.level), true)
					.setFooter("Save count: %,d \u2022 Last modified".format(attrs.cloud_save_info.saveCount))
					.setTimestamp(metadata.updated.toInstant())
				if (hasReachedMaxLevel) {
					embed.addField("Endurance max waves", Formatters.num.format(attrs.outpost_core_info.highestEnduranceWaveReached), true)
				}
				val users = source.queryUsers(attrs.outpost_core_info.accountsWithEditPermission.toList())
				embed.addFieldSeparate("Accounts with edit permissions", users) { it.displayName.escapeMarkdown() ?: it.id }
				source.complete(null, embed.build())
				Command.SINGLE_SUCCESS
			}
		)
}