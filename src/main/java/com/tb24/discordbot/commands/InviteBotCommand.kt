package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.BotConfig
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.Button
import net.dv8tion.jda.api.interactions.components.ButtonStyle
import net.dv8tion.jda.api.interactions.components.Component

class InviteBotCommand : BrigadierCommand("invite", "Sends a link to invite this bot to your server(s).") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			val selfUser = source.client.discord.selfUser
			val components = mutableListOf<Component>()
			components.add(Button.of(ButtonStyle.LINK, "https://discordapp.com/api/oauth2/authorize?client_id=${selfUser.id}&permissions=519232&scope=bot%20applications.commands", "Invite ${selfUser.name} to your server"))
			BotConfig.get().homeGuildInviteLink?.let { inviteLink ->
				components.add(Button.of(ButtonStyle.LINK, inviteLink, "Join our support server"))
			}
			source.complete("**Invite Links**", null, ActionRow.of(components))
			Command.SINGLE_SUCCESS
		}
}