package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.MentionArgument.Companion.getMention
import com.tb24.discordbot.commands.arguments.MentionArgument.Companion.mention
import com.tb24.discordbot.util.*
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.TextChannel

class EmbedCommand : BrigadierCommand("embed", "Shiver me embeds!") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { it.message.isFromGuild }
		.then(argument("channel", mention(Message.MentionType.CHANNEL))
			.then(argument("title and description separated by new line", greedyString())
				.executes {
					val channel = (getMention(it, "channel").firstOrNull()
						?: throw SimpleCommandExceptionType(LiteralMessage("No channels found.")).create()) as TextChannel
					val titleDescription = getString(it, "title and description separated by new line")
					execute(it.source, channel, titleDescription)
				}
			)
		)

	fun execute(source: CommandSourceStack, channel: TextChannel, titleDescription: String): Int {
		if (!source.member!!.hasPermission(channel, Permission.MESSAGE_WRITE)) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have permissions to send messages in that channel.")).create()
		}
		val args = titleDescription.split('\n').toMutableList()
		val title = args.removeFirst()
		val description = args.joinToString("\n")
		if (title.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("Title must not be empty.")).create()
		}
		val embed = EmbedBuilder().setTitle(title).setDescription(description).setColor(15767080)
		var content = ""
		val bMsg = source.channel.sendMessage("React with ➕ to add fields, 🗨 to add a message, or 📩 to send. *Preview:*\n$content").embed(embed.build()).allowedMentions(setOf()).complete()
		val botHasMessageManage = bMsg.member!!.hasPermission(Permission.MESSAGE_MANAGE)
		bMsg.addReaction("➕").queue()
		bMsg.addReaction("🗨").queue()
		bMsg.addReaction("📩").queue()
		fun updateMessage() {
			bMsg.editMessage("React with ➕ to add fields, 🗨 to add a message, or 📩 to send. *Preview:*\n$content").embed(embed.build()).allowedMentions(setOf()).queue()
		}
		while (true) {
			val choice = bMsg.awaitReactions({ _, user, _ -> user == source.author }, AwaitReactionsOptions().apply { time = 600000L; max = 1 }).await().firstOrNull()
				?: break
			if (botHasMessageManage) choice.removeReaction().queue()
			when (choice.reactionEmote.emoji) {
				"➕" -> {
					if (embed.fields.size == 25) {
						source.complete("❌ Embeds can only have 25 fields.")
						continue
					}
					val fieldPrompt = source.complete("Send the content for the field.")
					val fieldResponse = fieldPrompt.channel.awaitMessages({ _, user, _ -> user == source.author }, AwaitMessagesOptions().apply { time = 600000L; max = 1 }).await().firstOrNull()
						?: break
					if (botHasMessageManage) fieldPrompt.delete().queue()
					if (!fieldResponse.contentRaw.contains('\n')) {
						source.complete("❌ Invalid field content. Must be in this format:\n```\nField title\nField value\n```")
						continue
					}
					val fieldArgs = fieldResponse.contentRaw.split('\n').toMutableList()
					val fieldTitle = fieldArgs.removeFirst()
					val fieldValue = fieldArgs.joinToString("\n")
					embed.addField(fieldTitle, fieldValue, fieldValue.length <= 100)
					updateMessage()
				}
				"🗨" -> {
					val messagePrompt = source.complete("Send the content for the message.")
					val messageResponse = messagePrompt.channel.awaitMessages({ _, user, _ -> user == source.author }, AwaitMessagesOptions().apply { time = 600000L; max = 1 }).await().firstOrNull()
						?: break
					if (botHasMessageManage) messagePrompt.delete().queue()
					content = messageResponse.contentRaw
					updateMessage()
				}
				"📩" -> {
					channel.sendMessage(MessageBuilder().setContent(content).setEmbed(embed.build()).setAllowedMentions(setOf()).build()).complete()
					source.complete("✅ Successfully sent embed! Interaction design by a.bakedpotato.")
					break
				}
			}
		}
		return Command.SINGLE_SUCCESS
	}
}