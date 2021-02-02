package com.tb24.discordbot.commands

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.L10N
import com.tb24.discordbot.Rune
import com.tb24.discordbot.util.exec
import com.tb24.fn.EpicApi
import com.tb24.fn.model.mcpprofile.ProfileUpdate
import net.dv8tion.jda.api.entities.Message
import okhttp3.MediaType
import okhttp3.RequestBody

class ComposeMcpCommand : BrigadierCommand("composemcp", "Perform an arbitrary MCP profile operation. Usually used if such operation is not implemented yet.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::isBotDev)
		.then(argument("command", word())
			.executes { exec(it.source, getString(it, "command")) }
			.then(argument("profile ID", word())
				.executes { exec(it.source, getString(it, "command"), getString(it, "profile ID").toLowerCase()) }
				.then(argument("body", word())
					.executes { exec(it.source, getString(it, "command"), getString(it, "profile ID").toLowerCase(), getString(it, "body")) }
				)
			)
		)

	fun exec(source: CommandSourceStack, command: String, profileId: String = "common_core", bodyRaw: String = "{}"): Int {
		source.loading(L10N.format("generic.loading"))
		val profileManager = source.api.profileManager
		val isQueryProfile = command.equals("QueryProfile", true)
		if (isQueryProfile) {
			profileManager.localProfileGroup.profileRevisions.remove(profileId)
			profileManager.localProfileGroup.profileCommandRevisions[profileId] = -1
		}
		val body = RequestBody.create(MediaType.get("application/json"), bodyRaw)
		val data = source.api.okHttpClient.newCall(profileManager.makeClientCommandCall(command, profileId, null).request().newBuilder().post(body).build()).exec().body()!!.charStream().use(JsonParser::parseReader)
		val profileUpdate = EpicApi.GSON.fromJson(data, ProfileUpdate::class.java)
		profileManager.handleProfileUpdate(profileUpdate)

		fun result(data: JsonElement, lastNamePart: String) {
			val dataAsString = GsonBuilder().setPrettyPrinting().create().toJson(data)
			if (dataAsString.length > (Message.MAX_CONTENT_LENGTH - "```json\n```".length)) {
				source.loadingMsg!!.delete().queue()
				source.channel.sendFile(dataAsString.toByteArray(), "ComposeMCP-${source.api.currentLoggedIn.displayName}-${command}-${profileId}-${lastNamePart}.json").complete()
			} else {
				source.complete("```json\n$dataAsString```")
			}
		}

		if (isQueryProfile) {
			check(profileUpdate.profileChanges[0]["changeType"].asString == "fullProfileUpdate")
			result(profileUpdate.profileChanges[0], profileUpdate.profileRevision.toString())
		} else {
			result(data, if (profileUpdate.profileChangesBaseRevision != profileUpdate.profileRevision) "${profileUpdate.profileChangesBaseRevision}-to-${profileUpdate.profileRevision}" else profileUpdate.profileRevision.toString())
		}
		return Command.SINGLE_SUCCESS
	}
}