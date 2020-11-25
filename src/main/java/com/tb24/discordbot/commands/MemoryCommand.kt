package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder

class MemoryCommand : BrigadierCommand("memory", "Displays the JVM's current memory usage.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val max = Runtime.getRuntime().maxMemory()
			val total = Runtime.getRuntime().totalMemory()
			val free = Runtime.getRuntime().freeMemory()
			val used = total - free
			val sb = StringBuilder()
			sb.append("Mem: % 2d%% %03d/%03dMB".format(used * 100L / max, bytesToMb(used), bytesToMb(max))).append("\n")
			sb.append("Allocated: % 2d%% %03dMB".format(total * 100L / max, bytesToMb(total)))
			it.source.complete(sb.toString())
			Command.SINGLE_SUCCESS
		}

	private inline fun bytesToMb(bytes: Long) = bytes / 1024L / 1024L
}