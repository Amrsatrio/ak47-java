package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.BoolArgumentType.getBool
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.exec
import com.tb24.fn.model.AccountPrivacyResponse

class StatsPrivacyCommand : BrigadierCommand("statsprivacy", "Toggles or sets your stats visibility settings.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(argument("private?", bool())
			.executes { execute(it.source, getBool(it, "private?")) }
		)

	private fun execute(source: CommandSourceStack, preferredState: Boolean? = null): Int {
		source.ensureSession()
		source.loading(if (preferredState != null)
			"Changing your stats visibility"
		else
			"Toggling your stats visibility")
		val currentState = source.api.fortniteService.getAccountPrivacy(source.api.currentLoggedIn.id).exec().body()!!.optOutOfPublicLeaderboards
		val newState = preferredState ?: !currentState
		if (newState == currentState) {
			throw SimpleCommandExceptionType(LiteralMessage(if (currentState)
				"Your stats visibility is already **private**."
			else
				"Your stats visibility is already **public**.")).create()
		}
		source.api.fortniteService.setAccountPrivacy(source.api.currentLoggedIn.id, AccountPrivacyResponse().apply {
			optOutOfPublicLeaderboards = newState
		}).exec()
		source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
			.setDescription("✅ " + if (newState)
				"Your stats visibility is now **private**."
			else
				"Your stats visibility is now **public**.")
			.build())
		return Command.SINGLE_SUCCESS
	}
}