package com.tb24.discordbot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.tb24.fn.EpicApi
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.account.Token
import java.io.*
import java.nio.charset.StandardCharsets

object SessionPersister {
	private val file = File("config/sessions2.json")
	private val sessions: JsonObject = try {
		JsonParser.parseReader(file.reader()).asJsonObject
	} catch (e: FileNotFoundException) {
		JsonObject()
	} catch (e: JsonSyntaxException) {
		JsonObject()
	}

	fun get(sessionId: String): PersistedSession? =
		EpicApi.GSON.fromJson(sessions[sessionId], PersistedSession::class.java)

	fun set(session: Session) {
		sessions.add(session.id, EpicApi.GSON.toJsonTree(PersistedSession(session.api.userToken, session.api.currentLoggedIn.run { GameProfile(id, displayName) })))
		save()
	}

	fun remove(sessionId: String) = sessions.remove(sessionId).also { save() }

	private fun save() {
		file.parentFile.mkdirs()
		try {
			OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8)
				.use { EpicApi.GSON.toJson(sessions, it) }
		} catch (e: IOException) {
			e.printStackTrace()
		}
	}

	class PersistedSession(
		@JvmField var token: Token?,
		@JvmField var accountData: GameProfile?
	)
}