package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.tree.LiteralCommandNode
import com.tb24.discordbot.util.replyPaginated
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder

class HelpCommand : BrigadierCommand("help", "Shows all commands and their infos.", arrayListOf("?")) {
	private val HELP_ERROR = SimpleCommandExceptionType(LiteralMessage("Unknown command or insufficient permissions"))

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.message.replyPaginated(source.client.commandManager.commandMap.values.filter { it.registeredNode.canUse(source) }.sortedBy { it.name }) { content, page, pageCount ->
				MessageBuilder(EmbedBuilder()
					.setTitle(":mailbox_with_mail: Hey! Want some help?")
					.setColor(0x408BFA)
					.setFooter("Page %d of %d \u00b7 Powered by Brigadier".format(page + 1, pageCount))
					.apply {
						content.forEach {
							addField(source.prefix + it.name, it.description, true)
						}
					}
				).build()
			}
			Command.SINGLE_SUCCESS
		}
		.then(argument<CommandSourceStack, String>("command", greedyString())
			.executes { c ->
				val source = c.source
				val parseResults = dispatcher.parse(getString(c, "command"), source)
				if (parseResults.context.nodes.isEmpty()) {
					throw HELP_ERROR.create()
				}
				val firstNode = parseResults.context.nodes.first().node as LiteralCommandNode
				val lastNode = parseResults.context.nodes.last().node as? LiteralCommandNode ?: throw HELP_ERROR.create()
				val node = firstNode.redirect ?: firstNode
				val redirect = source.client.commandManager.redirects[node.name]
				val command = redirect ?: source.client.commandManager.commandMap[node.name]
				val smartUsage = dispatcher.getSmartUsage(lastNode, source)
				val aliasLine = when {
					firstNode.redirect != null -> "Alias for `${source.prefix}${node.name}`\n"
					redirect != null -> "Alias for `${source.prefix}${redirect.name}`\n"
					else -> ""
				}
				val embed = EmbedBuilder()
					.setTitle(source.prefix + parseResults.reader.string)
					.setDescription(aliasLine + (command?.description ?: "No description provided"))
					.setColor((redirect?.name ?: node.name).hashCode())
					.setFooter("Powered by Brigadier")
				if (smartUsage.isNotEmpty()) {
					embed.addField(if (smartUsage.size == 1) "Usage" else "Usages", smartUsage.values.joinToString("\n") { source.prefix + parseResults.reader.string + " " + it }, true)
				}
				if (command != null && command.aliases.isNotEmpty()) {
					embed.addField(if (command.aliases.size == 1) "Alias" else "Aliases", command.aliases.joinToString(", ") { "`$it`" }, true)
				}
				if (source.author.idLong == 624299014388711455L) {
					embed.addField("Tree", "```\n${source.client.commandManager.dumpCommand(lastNode)}```", false)
				}
				source.complete(null, embed.build())
				smartUsage.size
			}
		)
}