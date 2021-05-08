package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.rethinkdb.RethinkDB
import com.tb24.discordbot.util.StringUtil
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.EmbedBuilder
import java.lang.management.ManagementFactory

class InfoCommand : BrigadierCommand("info", "Shows general info about the bot.", arrayOf("memory", "mem")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			val embed = EmbedBuilder().setColor(COLOR_INFO)
				.addField("Sessions (incl. you)", Formatters.num.format(source.client.sessions.size), true)
				.addField("Premium users", Formatters.num.format(RethinkDB.r.table("members").run(source.client.dbConn).count()), true)
				.addField("Granters", Formatters.num.format(RethinkDB.r.table("admins").run(source.client.dbConn).count()), true)
				.addField("Memory", getMemoryInfo(), true)
				.addField("Uptime", StringUtil.formatElapsedTime(ManagementFactory.getRuntimeMXBean().uptime, true).toString(), true)
				.addField("Java version", System.getProperty("java.version"), true)
			source.complete(null, embed.build())
			Command.SINGLE_SUCCESS
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
}