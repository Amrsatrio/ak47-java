package com.tb24.discordbot.tasks

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.model.AutoClaimEntry
import com.tb24.discordbot.util.withDevice
import com.tb24.fn.model.account.GameProfile
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

abstract class AbstractAutoTask(val client: DiscordBot, val tableName: String) : Runnable {
	private val logger = LoggerFactory.getLogger(javaClass)
	val isRunning = AtomicBoolean()
	protected val random = Random()

	override fun run() {
		if (isRunning.get()) {
			throw SimpleCommandExceptionType(LiteralMessage("Task is already running.")).create()
		}
		isRunning.set(true)
		logger.info("Task started")
		client.ensureInternalSession()
		val autoClaimEntries = r.table(tableName).run(client.dbConn, AutoClaimEntry::class.java).shuffled(random)
		val users = client.internalSession.queryUsersMap(autoClaimEntries.map { it.id })
		for (entry in autoClaimEntries) {
			var attempts = 5
			while (attempts-- > 0) {
				logger.info("Performing ${text1()} for account ${entry.id}, attempt ${5 - attempts}")
				if (perform(entry, users)) {
					break
				}
			}
			delay()
		}
		isRunning.set(false)
	}

	private fun perform(entry: AutoClaimEntry, users: Map<String, GameProfile>): Boolean {
		val epicId = entry.id
		val discordId = entry.registrantId
		var displayName: String? = null
		try {
			displayName = users[epicId]?.displayName
			val user = client.discord.retrieveUserById(discordId).complete()
			val channel = user.openPrivateChannel().complete()
			val source = CommandSourceStack(client, channel)
			val savedDevice = if (displayName != null) client.savedLoginsManager.get(discordId, epicId) else null
			if (savedDevice == null) {
				disableAutoClaim(epicId)
				source.complete("Disabled ${text1()} of `$displayName` because we couldn't login to the account.")
				return true
			}
			withDevice(source, savedDevice, { performForAccount(source) }, { disableAutoClaim(epicId) }, users)
			return true
		} catch (e: ErrorResponseException) {
			client.dlog("Failed to ${text2()} for ${displayName ?: epicId} (registered by <@$discordId>)\n$e", null)
			logger.warn("Failed to ${text2()} for ${entry.id}", e)
			return true
		} catch (e: IOException) {
			client.dlog("Failed to ${text2()} for ${displayName ?: epicId} (registered by <@$discordId>). Retrying\n$e", null)
			logger.warn("Failed to ${text2()} for ${entry.id}. Retrying", e)
			return false
		}
	}

	private fun disableAutoClaim(accountId: String) {
		r.table(tableName).get(accountId).delete().run(client.dbConn)
	}

	protected abstract fun performForAccount(source: CommandSourceStack): Int

	protected open fun delay() {
		Thread.sleep(2500L + random.nextInt(2500))
	}

	protected abstract fun text1(): String
	protected abstract fun text2(): String
}