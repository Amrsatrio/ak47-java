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
		.then(literal("bulk")
			.executes { bulk(it.source) }
			.then(argument("users", UserArgument.users(100))
				.executes { bulk(it.source, lazy { UserArgument.getUsers(it, "users").values }) }
			)
			.then(literal("unfinished")
				.executes { bulk(it.source, null, true) }
				.then(argument("users", UserArgument.users(100))
					.executes { bulk(it.source, lazy { UserArgument.getUsers(it, "users").values }, true) }
				)
			)
		)

	private fun bulk(source: CommandSourceStack, users: Lazy<Collection<GameProfile>>? = null, unfinishedOnly: Boolean = false): Int {
		val names = arrayOf("S", "P", "C", "T")
		val ltr = arrayOf("stonewood", "plankerton", "cannyvalley")
		val entries = stwBulk(source, users) {
			val items = it.items
			val final = mutableListOf<String>()
			for (i in 1..4) {
				val templ = "Quest:outpostquest_t${i}_l"
				val hasQuest = items.values.any { it.templateId.startsWith(templ, true) }
				val completedLtr = if (i < 4) {
					items.values.any { it.templateId.startsWith("Quest:${ltr[i-1]}quest_launchrocket_d5", true) && it.attributes.get("completion_complete_launchrocket_$i")?.asInt == 1 }
				} else false
				val completedSsds = items.values.filter {
					val completion = it.attributes?.get("completion_complete_outpost_${i}_${it.templateId.substringAfter(templ)}")?.asInt
					it.templateId.startsWith(templ, true) && completion == 1
				}
				if ((completedSsds.isEmpty() && !hasQuest) || (unfinishedOnly && completedSsds.size == 10)) {
					continue
				}
				val name = names[i - 1]
				final.add("$name${completedSsds.size}")
				if (!completedLtr && i < 4 && completedSsds.size >= 6) {
					final.add("%sLTR".format(if (unfinishedOnly) name else ""))
				}
			}
			it.owner.displayName to final.joinToString(" ")
		}
		if (entries.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("%s storm shields completed.".format(if (unfinishedOnly) "All" else "No"))).create()
		}
		val embed = EmbedBuilder().setColor(COLOR_SUCCESS)
		for (entry in entries) {
			if (entry.second.isEmpty()) {
				continue
			}
			if (embed.fields.size == 25) {
				source.complete(null, embed.build())
				embed.clearFields()
			}
			embed.addField(entry.first, entry.second, true)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}