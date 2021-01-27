package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.dv8tion.jda.api.EmbedBuilder

class InviteBotCommand : BrigadierCommand("invite", "Sends a link to invite this bot to your server(s).") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			val selfUser = source.client.discord.selfUser
			source.complete(null, EmbedBuilder()
				.setTitle("Invite ${selfUser.name} to your server!", "https://discordapp.com/api/oauth2/authorize?client_id=${selfUser.id}&permissions=8&scope=bot")
				.setColor(COLOR_SUCCESS)
				.build())
			Command.SINGLE_SUCCESS
		}
}