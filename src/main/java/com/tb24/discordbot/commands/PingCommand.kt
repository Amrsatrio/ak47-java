package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.exec
import com.tb24.fn.network.AccountService
import com.tb24.fn.util.Formatters
import okhttp3.Request

class PingCommand : BrigadierCommand("ping", "Returns latency and API ping.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		val message = source.complete(Utils.loadingText("Pinging"))
		val start = System.currentTimeMillis()
		source.client.okHttpClient.newCall(Request.Builder().url(AccountService.BASE_URL_PROD + "api/version").build()).exec()
		val epicLatency = System.currentTimeMillis() - start
		message.editMessage(
			"🏓 Pong!\n" +
			"Discord API: ${Formatters.num.format(message.timeCreated.toInstant().toEpochMilli() - (source.interaction ?: source.message!!).timeCreated.toInstant().toEpochMilli())}ms\n" +
			"Discord Gateway: ${Formatters.num.format(source.jda.gatewayPing)}ms\n" +
			"Epic API: ${Formatters.num.format(epicLatency)}ms"
		).complete()
		return Command.SINGLE_SUCCESS
	}
}