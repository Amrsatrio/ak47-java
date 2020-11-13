package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.DiscordBot
import net.dv8tion.jda.api.Permission
import java.nio.charset.StandardCharsets

class PrefixCommand : BrigadierCommand("prefix", "Change prefix for the server/user. (Server admins only)", arrayOf("akprefix")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { it.message.isFromGuild && it.member!!.hasPermission(Permission.ADMINISTRATOR) }
		.executes {
			it.source.channel.sendMessage("Current prefix: `${it.source.prefix}`\nUse ${it.source.prefix}prefix <new prefix> to change it.").queue()
			Command.SINGLE_SUCCESS
		}
		.then(argument("new prefix", greedyString())
			.executes {
				val newPrefix = getString(it, "new prefix")
				if (!StandardCharsets.US_ASCII.newEncoder().canEncode(newPrefix)) {
					throw SimpleCommandExceptionType(LiteralMessage("No special characters allowed.")).create()
				}
				val prefixId = it.source.guild.idLong
				val currentPrefix = it.source.client.prefixMap[prefixId]
				if (newPrefix == currentPrefix?.prefix) {
					throw SimpleCommandExceptionType(LiteralMessage("The prefix is already $newPrefix.")).create()
				}
				val newPrefixObj = DiscordBot.PrefixConfig().apply {
					id = it.source.guild.id
					prefix = newPrefix
				}
				it.source.client.prefixMap[prefixId] = newPrefixObj
				if (currentPrefix != null) {
					r.table("prefix").update(newPrefixObj).run(it.source.client.dbConn)
				} else {
					r.table("prefix").insert(newPrefixObj).run(it.source.client.dbConn)
				}
				it.source.channel.sendMessage("✅ Prefix changed to `$newPrefix`").queue()
				Command.SINGLE_SUCCESS
			}
		)
}
