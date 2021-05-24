package com.tb24.discordbot

import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.util.Utils
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class GuildListeners(private val client: DiscordBot) : ListenerAdapter() {
	override fun onGuildLeave(event: GuildLeaveEvent) {
		r.table("prefix").get(event.guild.id).delete().run(client.dbConn)
	}

	override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
		if (event.guild.idLong == Utils.HOMEBASE_GUILD_ID && client.isProd) {
			grantRoleIfInTable(event, "members", "premium")
			grantRoleIfInTable(event, "admins", "admin.js")
		}
	}

	private fun grantRoleIfInTable(event: GuildMemberJoinEvent, tableName: String, roleName: String) {
		val guild = event.guild
		val member = event.member
		if (r.table(tableName).get(member.id).run(client.dbConn).first() != null) {
			guild.getRolesByName(roleName, true).firstOrNull()?.let {
				guild.addRoleToMember(member, it).queue()
			}
		}
	}
}