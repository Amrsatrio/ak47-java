package com.tb24.discordbot

import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.commands.BrigadierCommand
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class GuildListeners(private val client: DiscordBot) : ListenerAdapter() {
	override fun onGuildJoin(event: GuildJoinEvent) {
		val guild = event.guild
		val generalChannel = guild.textChannels.firstOrNull { it.name == "general" } ?: return
		val embed = EmbedBuilder().setColor(BrigadierCommand.COLOR_SUCCESS)
			.setTitle("👋 Hello %s, thanks for adding %s!".format(guild.name, client.discord.selfUser.name))
			.setDescription("- Use `{Prefix}help` or `{Prefix}commands` to see all my commands.\n- Server admins can change my prefix from `{Prefix}` by using `{Prefix}prefix <new name>`."
				.replace("{Prefix}", BotConfig.get().defaultPrefix))
		BotConfig.get().homeGuildInviteLink?.let {
			embed.appendDescription("\n- If you need more help, want to subscribe to bot updates and daily item shop, or get more info about premium, you can [visit our support server](%s).".format(it))
		}
		embed.appendDescription("\n\nEnjoy!")
		generalChannel.sendMessageEmbeds(embed.build()).queue()
	}

	override fun onGuildLeave(event: GuildLeaveEvent) {
		r.table("prefix").get(event.guild.id).delete().run(client.dbConn)
	}

	override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
		if (event.guild.idLong == BotConfig.get().homeGuildId && client.isProd) {
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