package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.StringUtil
import com.tb24.discordbot.util.exec

class ExchangeCommand : BrigadierCommand("exchange", "Generates an exchange code.", arrayListOf("xc")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			it.source.ensureSession()
			it.source.loading("Generating exchange code")
			it.source.api.accountService.getExchangeCode().exec().body()!!.apply {
				it.source.complete(code)
				it.source.complete("Valid for ${StringUtil.formatElapsedTime(expiresInSeconds * 1000L, true)}")
			}
			Command.SINGLE_SUCCESS
		}
}