package com.tb24.discordbot

import net.dv8tion.jda.api.events.message.guild.GuildMessageDeleteEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class GhostPingHandler(client: DiscordBot) : ListenerAdapter() {
	override fun onGuildMessageDelete(event: GuildMessageDeleteEvent) {
		// TODO figure out how to get the content of the deleted message
	}
}