package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.commands.arguments.MentionArgument.Companion.getMention
import com.tb24.discordbot.commands.arguments.MentionArgument.Companion.mention
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.ChannelType
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import java.time.Instant

class GrantCommand : BrigadierCommand("grant", "Grants a user premium access.", arrayOf("auser")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { r.table("admins").get(it.author.id).run(it.client.dbConn).first() != null }
		.then(argument("target", mention(Message.MentionType.USER))
			.executes {
				premium(it.source, (getMention(it, "target").firstOrNull()
					?: throw SimpleCommandExceptionType(LiteralMessage("No users found.")).create()) as User, false)
			}
		)
}

class RevokeCommand : BrigadierCommand("revoke", "Revokes a user's premium access.", arrayOf("ruser")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { r.table("admins").get(it.author.id).run(it.client.dbConn).first() != null }
		.then(argument("target", mention(Message.MentionType.USER))
			.executes {
				premium(it.source, (getMention(it, "target").firstOrNull()
					?: throw SimpleCommandExceptionType(LiteralMessage("No users found.")).create()) as User, true)
			}
		)
}

class GrantRoledCommand : BrigadierCommand("grantroled", "Grants premium to everyone with premium role.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { r.table("admins").get(it.author.id).run(it.client.dbConn).first() != null }
		.executes { c ->
			val source = c.source
			val guild = source.client.discord.getGuildById(648556726672556048L)
				?: throw SimpleCommandExceptionType(LiteralMessage("Guild not found.")).create()
			val membersWithRole = guild.loadMembers().get().filter { m -> m.roles.any { it.name.equals("premium", true) } }
			val granted = r.table("members").run(source.client.dbConn).toList()
			val hasRoleButNotGranted = membersWithRole.filter { m -> !granted.any { (it as Map<*, *>)["id"] == m.id } }
			if (hasRoleButNotGranted.isEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage("No one to grant.")).create()
			}
			r.table("members").insert(hasRoleButNotGranted.map { mapOf("id" to it.id) }).run(source.client.dbConn)
			source.complete("✅ Granted premium to ${Formatters.num.format(hasRoleButNotGranted.size)} members")
			Command.SINGLE_SUCCESS
		}
}

fun premium(source: CommandSourceStack, target: User, remove: Boolean/*, secret: String?*/): Int {
	//val tableName = if (secret != null && secret == MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).printHexBinary(false)) "admins" else "members"
	val tableName = "members"
	if (r.table(tableName).get(target.id).run(source.client.dbConn).first() != null != remove) {
		throw SimpleCommandExceptionType(LiteralMessage((if (remove) "%s is not activated." else "%s is already activated.").format(target.asMention))).create()
	}
	if (remove) {
		r.table(tableName).get(target.id).delete()
	} else {
		r.table(tableName).insert(mapOf("id" to target.id))
	}.run(source.client.dbConn)
	source.complete(null, EmbedBuilder()
		.setTitle((if (remove) "Removed premium from %s" else "Granted premium to %s").format(target.asTag))
		.setThumbnail(target.avatarUrl)
		.setFooter("Requested by %s".format(source.author.asTag), source.author.avatarUrl)
		.setColor(if (remove) 0xFF0000 else 0x40FAA1)
		.setTimestamp(Instant.now())
		.build())
	source.client.dlog(null, EmbedBuilder()
		.setTitle(if (remove) "Premium removed" else "Premium granted")
		.setThumbnail(target.avatarUrl)
		.addField("Target User", "%s (tag: %s)".format(target.asMention, target.asTag), false)
		.addField("Requested By", "%s (tag: %s)".format(source.author.asMention, source.author.asTag), false)
		.addField("Requested At", if (source.isFromType(ChannelType.PRIVATE)) "Direct Message" else if (source.isFromType(ChannelType.TEXT)) "Guild: %s".format(source.guild.name) else "Unknown", false)
		.setColor(if (remove) 0xFF0000 else 0x40FAA1)
		.setTimestamp(Instant.now())
		.build())
	source.client.discord.getGuildById(648556726672556048L)?.let { homebaseGuild ->
		val guildMember = homebaseGuild.getMember(target)
		val role = homebaseGuild.getRolesByName("premium", true).firstOrNull()
		if (guildMember != null && role != null) {
			if (remove) {
				homebaseGuild.removeRoleFromMember(guildMember, role)
			} else {
				homebaseGuild.addRoleToMember(guildMember, role)
			}.complete()
		}
	}
	return Command.SINGLE_SUCCESS
}