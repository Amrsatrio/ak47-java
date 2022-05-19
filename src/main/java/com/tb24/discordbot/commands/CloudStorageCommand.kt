package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.AttachmentUpload
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.exec
import com.tb24.fn.util.Utils
import com.tb24.fn.util.Utils.FLAG_SHORTER
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat

class CloudStorageCommand : BrigadierCommand("cloudstorage", "List, download, or upload your cloud saved game settings.", arrayOf("cs")) {
	private val df = SimpleDateFormat()

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Enumerating user files")
			val files = source.api.fortniteService.enumerateUserFiles(source.api.currentLoggedIn.id).exec().body()!!
			if (files.isEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage("You have no Fortnite cloud files")).create()
			}
			val sb = StringBuilder("```\n")
			sb.append("%-50s %-17s %s".format("Name", "Uploaded", "Size"))
			files.forEach {
				sb.append('\n').append("%-50s %-17s %7s".format(it.filename, df.format(it.uploaded), Utils.formatBytes(it.length, FLAG_SHORTER).run { "$value $units" }))
			}
			sb.append("\n```")
			source.complete(sb.toString())
			Command.SINGLE_SUCCESS
		}
		.then(argument("file name", greedyString())
			.executes { c ->
				val source = c.source
				val fileName = getString(c, "file name")
				source.ensureSession()
				val fileToUpload = source.message?.attachments?.firstOrNull()
				if (fileToUpload != null) {
					source.loading("Uploading $fileName")
					fileToUpload.proxy.download().await().use {
						val body = it.readBytes().toRequestBody("application/octet-stream".toMediaType())
						source.api.fortniteService.writeUserFile(source.api.currentLoggedIn.id, fileName, body).exec()
					}
					source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
						.setTitle("✅ Uploaded $fileName")
						.build())
				} else {
					source.loading("Downloading $fileName")
					val response = source.api.fortniteService.readUserFile(source.api.currentLoggedIn.id, fileName).exec().body()!!
					response.byteStream().use {
						source.complete(AttachmentUpload(it, fileName))
					}
				}
				Command.SINGLE_SUCCESS
			}
		)
}