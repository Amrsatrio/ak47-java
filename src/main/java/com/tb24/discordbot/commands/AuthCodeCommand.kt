package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.fn.util.EAuthClient

class AuthCodeCommand : BrigadierCommand("authcode", "Generates a link to the authorization code generation page.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(argument("auth client", greedyString())
			.executes { execute(it.source, getString(it, "auth client")) }
		)

	private fun execute(source: CommandSourceStack, clientId: String? = null): Int {
		source.ensureSession()
		val clientId = if (clientId != null) {
			EAuthClient.values().firstOrNull { it.name.replace("_", "").equals(clientId, true) }?.clientId ?: clientId
		} else {
			source.api.userToken.client_id
		}
		source.complete(source.generateUrl("https://www.epicgames.com/id/api/redirect?clientId=$clientId&responseType=code"))
		return Command.SINGLE_SUCCESS
	}
}