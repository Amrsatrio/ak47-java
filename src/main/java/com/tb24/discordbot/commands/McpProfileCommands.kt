package com.tb24.discordbot.commands

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.L10N
import com.tb24.discordbot.util.AttachmentUpload
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.exec
import com.tb24.fn.EpicApi
import com.tb24.fn.model.mcpprofile.ProfileUpdate
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import net.dv8tion.jda.api.entities.Message

class ComposeMcpCommand : BrigadierCommand("composemcp", "Perform an arbitrary MCP profile operation. Usually used if such operation is not implemented yet.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("command", word())
			.executes { exec(it.source, getString(it, "command")) }
			.then(argument("profile ID", word())
				.executes { c ->
					val source = c.source
					val bodyFile = source.message?.attachments?.firstOrNull()
					if (bodyFile != null) {
						val maxAcceptedFileSize = 1 shl 20
						if (bodyFile.size > maxAcceptedFileSize) {
							throw SimpleCommandExceptionType(LiteralMessage("Request body may not be larger than 1 MB.")).create()
						}
						val body = bodyFile.retrieveInputStream().await().bufferedReader().use { it.readText() }
						exec(source, getString(c, "command"), getString(c, "profile ID").toLowerCase(), body)
					} else {
						exec(source, getString(c, "command"), getString(c, "profile ID").toLowerCase())
					}
				}
				.then(argument("body", greedyString())
					.executes { exec(it.source, getString(it, "command"), getString(it, "profile ID").toLowerCase(), getString(it, "body")) }
				)
			)
		)

	fun exec(source: CommandSourceStack, command: String, profileId: String = "common_core", bodyRaw: String = "{}"): Int {
		source.ensurePremium("Perform arbitrary MCP profile operations")
		val parsedJson = try {
			JsonParser.parseString(bodyRaw)
		} catch (e: JsonSyntaxException) {
			throw SimpleCommandExceptionType(LiteralMessage("Malformed JSON: " + e.message?.substringAfter("Use JsonReader.setLenient(true) to accept malformed JSON at "))).create()
		}
		source.ensureSession()
		source.loading(L10N.format("generic.loading"))
		val profileManager = source.api.profileManager
		val isQueryProfile = command.equals("QueryProfile", true)
		if (isQueryProfile) {
			profileManager.localProfileGroup.profileRevisions.remove(profileId)
			profileManager.localProfileGroup.profileCommandRevisions[profileId] = -1
		} else {
			profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
		}
		val originalCall = profileManager.makeClientCommandCall(command, profileId, parsedJson)
		val data = source.api.okHttpClient.newCall(originalCall.request()).exec().body()!!.charStream().use(JsonParser::parseReader)
		val profileUpdate = EpicApi.GSON.fromJson(data, ProfileUpdate::class.java)
		profileManager.handleProfileUpdate(profileUpdate)

		fun result(data: JsonElement, lastNamePart: String) {
			val dataAsString = GsonBuilder().setPrettyPrinting().create().toJson(data)
			if (dataAsString.length > (Message.MAX_CONTENT_LENGTH - "```json\n```".length)) {
				source.complete(AttachmentUpload(dataAsString.toByteArray(), "ComposeMCP-${source.api.currentLoggedIn.displayName}-${command}-${profileId}-${lastNamePart}.json"))
			} else {
				source.complete("```json\n$dataAsString```")
			}
		}

		if (isQueryProfile) {
			val firstProfileChange = profileUpdate.profileChanges[0]
			check(firstProfileChange["changeType"].asString == "fullProfileUpdate")
			result(firstProfileChange["profile"], profileUpdate.profileRevision.toString())
		} else {
			result(data, if (profileUpdate.profileChangesBaseRevision != profileUpdate.profileRevision) "${profileUpdate.profileChangesBaseRevision}-to-${profileUpdate.profileRevision}" else profileUpdate.profileRevision.toString())
		}
		return Command.SINGLE_SUCCESS
	}
}