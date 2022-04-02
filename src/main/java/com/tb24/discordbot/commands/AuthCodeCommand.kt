package com.tb24.discordbot.commands

import com.google.gson.JsonObject
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.performWebApiRequest
import com.tb24.fn.util.EAuthClient
import com.tb24.fn.util.getString

class AuthCodeCommand : BrigadierCommand("authcode", "Generates a link to the authorization code generation page.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(argument("auth client", greedyString())
			.executes { execute(it.source, getString(it, "auth client")) }
		)

	private fun execute(source: CommandSourceStack, clientId: String? = null): Int {
		source.ensureSession()
		source.warnCodeToken()
		val clientId = if (clientId != null) {
			EAuthClient.values().firstOrNull { it.name.replace("_", "").equals(clientId, true) }?.clientId ?: clientId
		} else {
			source.api.userToken.client_id
		}
		source.complete(source.api.performWebApiRequest<JsonObject>("https://www.epicgames.com/id/api/redirect?clientId=$clientId&responseType=code").getString("authorizationCode"))
		source.complete("⚠ **Do not share the code with anyone else!** This will let them log into your account.\nClient: ${EAuthClient.values().firstOrNull { it.clientId == clientId }?.name ?: clientId}")
		return Command.SINGLE_SUCCESS
	}
}