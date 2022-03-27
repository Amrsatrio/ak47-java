package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.relativeFromNow

class ExchangeCommand : BrigadierCommand("exchange", "Generates an exchange code for logging in in another location.", arrayOf("xc")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.warnCodeToken()
		source.loading("Generating exchange code")
		source.api.accountService.exchangeCode.exec().body()!!.apply {
			source.complete(code)
			source.complete("⚠ **Do not share the code with anyone else!** This will let them log into your account.\nExpires ${(System.currentTimeMillis() + expiresInSeconds * 1000L).relativeFromNow(true)}. Use `${source.prefix}logout` to invalidate the code.")
		}
		return Command.SINGLE_SUCCESS
	}
}