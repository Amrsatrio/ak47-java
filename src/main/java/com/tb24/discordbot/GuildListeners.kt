package com.tb24.discordbot

import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class GuildListeners(client: DiscordBot) : ListenerAdapter() {
	override fun onGuildLeave(event: GuildLeaveEvent) {
	}
}