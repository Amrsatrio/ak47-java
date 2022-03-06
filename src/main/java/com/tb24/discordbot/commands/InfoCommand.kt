package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.rethinkdb.RethinkDB
import com.tb24.discordbot.Rune
import com.tb24.discordbot.util.StringUtil
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDAInfo
import java.lang.management.ManagementFactory

class InfoCommand : BrigadierCommand("info", "Shows general info about the bot.", arrayOf("memory", "mem")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(literal("guilds").requires(Rune::isBotDev).executes { executeGuilds(it.source) })

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		val embed = EmbedBuilder().setColor(COLOR_INFO)
			.addField("Sessions (incl. you)", Formatters.num.format(source.client.sessions.size), true)
			.addField("Premium users", Formatters.num.format(RethinkDB.r.table("members").run(source.client.dbConn).count()), true)
			.addField("Granters", Formatters.num.format(RethinkDB.r.table("admins").run(source.client.dbConn).count()), true)
			.addField("Servers", Formatters.num.format(source.client.discord.guilds.size), true)
			.addField("Cached users", Formatters.num.format(source.client.discord.users.size), true)
			.addField("Uptime", StringUtil.formatElapsedTime(ManagementFactory.getRuntimeMXBean().uptime, true).toString(), true)
			.addField("Memory", getMemoryInfo(), true)
			.addField("Versions", "Java: `%s`\nJDA: `%s`".format(System.getProperty("java.version"), JDAInfo.VERSION), true)
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun executeGuilds(source: CommandSourceStack): Int {
		source.complete(source.client.commandManager.byGuild.entries.sortedByDescending { it.value }.take(25).joinToString("\n") {
			val guild = source.jda.getGuildById(it.key)
			"%s (%s): %s".format(guild?.name, it.key, it.value)
		})
		return Command.SINGLE_SUCCESS
	}
}

class MemoryCommand : BrigadierCommand("memory", "Displays the JVM's current memory usage.", arrayOf("mem")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			it.source.complete(getMemoryInfo())
			Command.SINGLE_SUCCESS
		}

	override fun getSlashCommand() = newCommandBuilder().executes {
		it.interaction.reply(getMemoryInfo()).complete()
		Command.SINGLE_SUCCESS
	}
}

private fun getMemoryInfo(): String {
	val runtime = Runtime.getRuntime()
	val max = runtime.maxMemory()
	val total = runtime.totalMemory()
	val free = runtime.freeMemory()
	val used = total - free
	val sb = StringBuilder()
	sb.append("Mem: % 2d%% %03d/%03dMB".format(used * 100L / max, bytesToMb(used), bytesToMb(max))).append("\n")
	sb.append("Allocated: % 2d%% %03dMB".format(total * 100L / max, bytesToMb(total)))
	return sb.toString()
}

private inline fun bytesToMb(bytes: Long) = bytes / 1024L / 1024L