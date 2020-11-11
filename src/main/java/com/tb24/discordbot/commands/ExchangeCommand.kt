package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.StringUtil
import com.tb24.discordbot.util.exec

class ExchangeCommand : BrigadierCommand("exchange", "Generates an exchange code.", arrayListOf("xc")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Generating exchange code")
			source.api.accountService.getExchangeCode().exec().body()!!.apply {
				source.complete(code)
				source.complete("Valid for ${StringUtil.formatElapsedTime(expiresInSeconds * 1000L, true)}. Use `${source.prefix}logout` to invalidate the code.")
			}
			Command.SINGLE_SUCCESS
		}
}