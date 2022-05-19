package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.AttachmentUpload
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.commandName
import com.tb24.discordbot.util.exec
import com.tb24.fn.util.FFortSaveGame
import com.tb24.uasset.JWPSerializer
import net.dv8tion.jda.api.entities.Message

class DumpSaveGameCommand : BrigadierCommand("dumpsav", "Prints out the data inside a sav file in a human readable format.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("cloud storage file name", StringArgumentType.greedyString())
			.executes { c ->
				val source = c.source
				val fileName = StringArgumentType.getString(c, "cloud storage file name")
				source.ensureSession()
				source.loading("Downloading $fileName")
				val call = source.api.fortniteService.readUserFile(source.api.currentLoggedIn.id, fileName)
				val response = call.exec().body()!!
				display(source, FFortSaveGame(response.bytes(), call.request().url.toString()))
			}
		)
		.executes { c ->
			val source = c.source
			val attachedFile = source.message?.attachments?.firstOrNull()
				?: throw SimpleCommandExceptionType(LiteralMessage("Please attach a file, or use `${source.prefix}${c.commandName} <cloud storage file name>`.")).create()
			if (!attachedFile.fileName.endsWith(".sav", true)) {
				throw SimpleCommandExceptionType(LiteralMessage("Not valid save game file.")).create()
			}
			val maxAcceptedFileSize = 5 shl 20
			if (attachedFile.size > maxAcceptedFileSize) {
				throw SimpleCommandExceptionType(LiteralMessage("<:amogus:866343868328443957>")).create()
			}
			display(source, FFortSaveGame(attachedFile.proxy.download().await().readBytes(), attachedFile.url))
		}

	private fun display(source: CommandSourceStack, saveGame: FFortSaveGame): Int {
		val s = JWPSerializer.GSON.newBuilder().setPrettyPrinting().create().toJson(saveGame)
		if (("```json\n\n```".length + s.length) > Message.MAX_CONTENT_LENGTH) {
			val fileName = saveGame.name.substringAfterLast('/').substringBeforeLast('.') + ".json"
			source.complete(AttachmentUpload(s.toByteArray(), fileName))
		} else {
			source.complete("```json\n$s\n```")
		}
		return Command.SINGLE_SUCCESS
	}
}