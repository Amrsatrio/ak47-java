package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.BotConfig
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.ItemComponent
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle

class InviteBotCommand : BrigadierCommand("invite", "Sends a link to invite this bot to your server(s).") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		val selfUser = source.jda.selfUser
		val components = mutableListOf<ItemComponent>()
		components.add(Button.of(ButtonStyle.LINK, "https://discordapp.com/api/oauth2/authorize?client_id=${selfUser.id}&permissions=519232&scope=bot%20applications.commands", "Invite ${selfUser.name} to your server"))
		BotConfig.get().homeGuildInviteLink?.let { inviteLink ->
			components.add(Button.of(ButtonStyle.LINK, inviteLink, "Join our support server"))
		}
		source.complete("**Invite Links**", null, ActionRow.of(components))
		return Command.SINGLE_SUCCESS
	}
}