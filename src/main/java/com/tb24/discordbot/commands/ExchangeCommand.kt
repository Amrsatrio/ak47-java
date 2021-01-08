package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.relativeFromNow

class ExchangeCommand : BrigadierCommand("exchange", "Generates an exchange code.", arrayOf("xc")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Generating exchange code")
			source.api.accountService.exchangeCode.exec().body()!!.apply {
				source.complete(code)
				source.complete("Expires ${(System.currentTimeMillis() + expiresInSeconds * 1000L).relativeFromNow()}. Use `${source.prefix}logout` to invalidate the code.")
			}
			Command.SINGLE_SUCCESS
		}
}