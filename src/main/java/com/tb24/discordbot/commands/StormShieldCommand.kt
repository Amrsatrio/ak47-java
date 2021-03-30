package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.FortOutpostItem
import com.tb24.fn.util.Formatters
import java.util.*

class StormShieldCommand : BrigadierCommand("ssd", "Shows info about your storm shield.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("zone", word())
			.executes { c ->
				val source = c.source
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
				val outpostItem = metadata.items.values.firstOrNull { it.templateId == lookupTemplateId }
					?: throw SimpleCommandExceptionType(LiteralMessage("You don't have a " + FortItemStack(lookupTemplateId, 1).displayName)).create()
				val outpost = outpostItem.getAttributes(FortOutpostItem::class.java)
				val hasReachedMaxLevel = outpost.level >= 10
				val embed = source.createEmbed()
					.setTitle(outpostItem.displayName)
					.addField("Level", if (hasReachedMaxLevel) "%,d (Endurance unlocked!)".format(outpost.level) else Formatters.num.format(outpost.level), true)
					.setFooter("Save count: %,d · Last modified".format(outpost.cloud_save_info.saveCount))
					.setTimestamp(metadata.updated.toInstant())
				if (hasReachedMaxLevel) {
					embed.addField("Endurance max waves", Formatters.num.format(outpost.outpost_core_info.highestEnduranceWaveReached), true)
				}
				val users = source.queryUsers(outpost.outpost_core_info.accountsWithEditPermission.toList())
				embed.addField("Accounts with edit permissions", if (users.isNotEmpty()) users.mapIndexed { i, user ->
					"%,d. %s".format(i + 1, user.displayName ?: user.id)
				}.joinToString("\n") else "No entries", false)
				source.complete(null, embed.build())
				Command.SINGLE_SUCCESS
			}
		)
}