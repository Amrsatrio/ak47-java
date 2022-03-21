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
import com.tb24.discordbot.util.AwaitMessagesOptions
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.awaitMessages
import com.tb24.discordbot.util.awaitOneInteraction
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.entities.GuildChannel
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle

class EmbedCommand : BrigadierCommand("embed", "Shiver me embeds!") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { it.member?.hasPermission(it.channel as GuildChannel, Permission.MESSAGE_MANAGE) == true }
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
		if (!source.member!!.hasPermission(channel, Permission.MESSAGE_SEND)) {
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
		val buttons = mutableListOf<Button>()
		buttons.add(Button.of(ButtonStyle.SECONDARY, "addField", "Add field", Emoji.fromUnicode("➕")))
		buttons.add(Button.of(ButtonStyle.SECONDARY, "addMessage", "Add message", Emoji.fromUnicode("🗨")))
		buttons.add(Button.of(ButtonStyle.PRIMARY, "send", "Send", Emoji.fromUnicode("📩")))
		val bMsg = source.channel.sendMessage("*Preview:*\n$content").setEmbeds(embed.build()).setActionRow(*buttons.toTypedArray()).allowedMentions(emptySet()).complete()
		val botHasMessageManage = bMsg.member!!.hasPermission(Permission.MESSAGE_MANAGE)
		fun updateMessage() {
			bMsg.editMessage("*Preview:*\n$content").setEmbeds(embed.build()).setActionRow(*buttons.toTypedArray()).allowedMentions(emptySet()).queue()
		}
		while (true) {
			when (bMsg.awaitOneInteraction(source.author, false, 600000L).componentId) {
				"addField" -> {
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
				"addMessage" -> {
					val messagePrompt = source.complete("Send the content for the message.")
					val messageResponse = messagePrompt.channel.awaitMessages({ _, user, _ -> user == source.author }, AwaitMessagesOptions().apply { time = 600000L; max = 1 }).await().firstOrNull()
						?: break
					if (botHasMessageManage) messagePrompt.delete().queue()
					content = messageResponse.contentRaw
					updateMessage()
				}
				"send" -> {
					channel.sendMessage(MessageBuilder(content).setEmbeds(embed.build()).setAllowedMentions(emptySet()).build()).complete()
					source.complete("✅ ${source.author.asMention}, successfully sent embed! Interaction design by a.bakedpotato.")
					break
				}
			}
		}
		return Command.SINGLE_SUCCESS
	}
}