package com.tb24.discordbot.tasks

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.commands.claimFreeLlamas
import com.tb24.discordbot.model.AutoClaimEntry
import com.tb24.discordbot.util.withDevice
import com.tb24.fn.model.account.GameProfile
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class AutoFreeLlamaTask(val client: DiscordBot) : Runnable {
	companion object {
		@JvmField
		val TASK_IS_RUNNING = AtomicBoolean()
	}

	val logger = LoggerFactory.getLogger("AutoFreeLlama")
	val random = Random()

	override fun run() {
		if (TASK_IS_RUNNING.get()) {
			throw SimpleCommandExceptionType(LiteralMessage("Task is already running.")).create()
		}
		TASK_IS_RUNNING.set(true)
		DiscordBot.LOGGER.info("Executing AutoFreeLlamaTask")
		client.ensureInternalSession()
		val autoClaimEntries = r.table("auto_llama").run(client.dbConn, AutoClaimEntry::class.java).shuffled(random)
		val users = client.internalSession.queryUsersMap(autoClaimEntries.map { it.id })
		for (entry in autoClaimEntries) {
			var attempts = 5
			while (attempts-- > 0) {
				logger.info("Performing auto free llama claiming for account ${entry.id}, attempt ${5 - attempts}")
				if (perform(entry, users)) {
					break
				}
			}
			Thread.sleep(random.nextInt(1000).toLong()) // We don't have much time
		}
		TASK_IS_RUNNING.set(false)
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
			if (displayName == null) {
				disableAutoClaim(epicId)
				source.complete("Disabled automatic free llama claiming of `$epicId` because that account has been deleted or deactivated.")
				return true
			}
			val savedDevice = client.savedLoginsManager.get(discordId, epicId)
			if (savedDevice == null) {
				disableAutoClaim(epicId)
				source.complete("Disabled automatic free llama claiming of `$displayName` because we couldn't find a saved login.")
				return true
			}
			withDevice(source, savedDevice, { claimFreeLlamas(source) }, { disableAutoClaim(epicId) })
			return true
		} catch (e: ErrorResponseException) {
			client.dlog("Failed to claim free llamas for ${displayName ?: epicId} (registered by <@$discordId>)\n$e", null)
			logger.warn("Failed to claim free llamas for ${entry.id}", e)
			return true
		} catch (e: IOException) {
			client.dlog("Failed to claim free llamas for ${displayName ?: epicId} (registered by <@$discordId>). Retrying\n$e", null)
			logger.warn("Failed to claim free llamas for ${entry.id}. Retrying", e)
			return false
		}
	}

	private fun disableAutoClaim(accountId: String) {
		r.table("auto_llama").get(accountId).delete().run(client.dbConn)
	}
}