package com.tb24.discordbot.commands

import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.relativeFromNow
import com.tb24.fn.network.AccountService.GrantType
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import java.util.*

class AccessTokenCommand : BrigadierCommand("token", "Sends your active Epic access token.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(argument("client", word())
			.executes { execute(it.source, getString(it, "client")) }
			.then(literal("internal")
				.executes { executeClientToken(it.source, getString(it, "client")) }
			)
		)

	private fun execute(source: CommandSourceStack, client: String? = null): Int {
		source.ensureSession()
		var token = if (client == null) {
			if (!source.api.userToken.access_token.startsWith("eg1~")) {
				throw SimpleCommandExceptionType(LiteralMessage("Your active token is not a JWT.")).create()
			}
			source.api.userToken.access_token
		} else {
			source.session.getApiForOtherClient(client.replace("_", "").run {
				EAuthClient.values().firstOrNull { it.name.replace("_", "").equals(this, true) }
					?: throw SimpleCommandExceptionType(LiteralMessage("Invalid auth client `$client`. Valid clients are:```\n${EAuthClient.values().joinToString()}```")).create()
			}).userToken.access_token
		}
		token = token.substring(4)
		val payload = token.split(".")[1]
		val payloadJson = String(Base64.getUrlDecoder().decode(payload))
		val payloadObj = JsonParser.parseString(payloadJson).asJsonObject
		val jti = payloadObj.get("jti").asString
		val expires = payloadObj.get("exp").asInt
		val embed = source.createEmbed().addField("Access token", jti, false).addField("Expires", (expires * 1000L).relativeFromNow(true), false)
		if (source.guild == null) {
			source.complete(null, embed.build())
		} else {
			try {
				val detailsMessage = source.author.openPrivateChannel()
					.flatMap { it.sendMessageEmbeds(embed.build()) }
					.complete()
				source.complete(null, source.createEmbed().setDescription("[Check your DMs for details.](%s)".format(detailsMessage.jumpUrl)).build())
			} catch (e: ErrorResponseException) {
				source.complete(null, source.createEmbed().setDescription("We couldn't DM you the details.").build())
			}
		}
		return Command.SINGLE_SUCCESS
	}

	private fun executeClientToken(source: CommandSourceStack, client: String): Int {
		val authClient = client.replace("_", "").run {
			EAuthClient.values().firstOrNull { it.name.replace("_", "").equals(this, true) }
				?: throw SimpleCommandExceptionType(LiteralMessage("Invalid auth client `$client`. Valid clients are:```\n${EAuthClient.values().joinToString()}```")).create()
		}
		val token = source.api.accountService.getAccessToken(authClient.asBasicAuthString(), GrantType.clientCredentials(), null, null).exec().body()!!
		source.complete(token.access_token)
		source.complete("Expires: ${(System.currentTimeMillis() + token.expires_in * 1000L).relativeFromNow(true)}")
		return Command.SINGLE_SUCCESS
	}
}