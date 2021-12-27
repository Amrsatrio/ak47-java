package com.tb24.discordbot.commands

import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import java.util.*

class AccessTokenCommand : BrigadierCommand("token", "Sends your active Epic access token.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		var token = source.api.userToken.access_token
		if (!token.startsWith("eg1~")) {
			throw SimpleCommandExceptionType(LiteralMessage("Your active token is not a JWT.")).create()
		}
		token = token.substring(4)
		val payload = token.split(".")[1]
		val payloadJson = String(Base64.getUrlDecoder().decode(payload))
		val payloadObj = JsonParser.parseString(payloadJson).asJsonObject
		val jti = payloadObj.get("jti").asString
		val embed = source.createEmbed().addField("Access token", jti, false)
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
}