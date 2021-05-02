package com.tb24.discordbot

import com.rethinkdb.RethinkDB.r
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class GuildListeners(private val client: DiscordBot) : ListenerAdapter() {
	override fun onGuildLeave(event: GuildLeaveEvent) {
		r.table("prefix").get(event.guild.id).delete().run(client.dbConn)
	}
}