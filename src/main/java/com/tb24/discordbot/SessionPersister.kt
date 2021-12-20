package com.tb24.discordbot

import com.rethinkdb.RethinkDB.r
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.account.Token

object SessionPersister {
	@JvmStatic
	lateinit var client: DiscordBot

	@Synchronized
	fun get(sessionId: String) = r.table("sessions").get(sessionId).run(client.dbConn, PersistedSession::class.java).first()

	@Synchronized
	fun set(session: Session) {
		val persistedSession = PersistedSession(session.id, session.api.userToken, session.api.currentLoggedIn.run { GameProfile(id, epicDisplayName) })
		val existing = get(session.id)
		if (existing != null) {
			r.table("sessions").update(persistedSession).run(client.dbConn)
		} else {
			r.table("sessions").insert(persistedSession).run(client.dbConn)
		}
	}

	@Synchronized
	fun remove(sessionId: String) = r.table("sessions").get(sessionId).delete().run(client.dbConn)

	class PersistedSession(
		@JvmField var id: String,
		@JvmField var token: Token?,
		@JvmField var accountData: GameProfile?
	) {
		constructor() : this("", null, null)
	}
}