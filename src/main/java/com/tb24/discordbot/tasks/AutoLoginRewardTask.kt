package com.tb24.discordbot.tasks

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.commands.notifyDailyRewardsClaimed
import com.tb24.discordbot.model.AutoClaimEntry
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.withDevice
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.ClaimLoginReward
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin
import com.tb24.fn.model.mcpprofile.notifications.DailyRewardsNotification
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class AutoLoginRewardTask(val client: DiscordBot) : Runnable {
	companion object {
		@JvmField
		val TASK_IS_RUNNING = AtomicBoolean()
	}

	val logger = LoggerFactory.getLogger("AutoLoginReward")
	val random = Random()

	override fun run() {
		if (TASK_IS_RUNNING.get()) {
			throw SimpleCommandExceptionType(LiteralMessage("Task is already running.")).create()
		}
		TASK_IS_RUNNING.set(true)
		DiscordBot.LOGGER.info("Executing AutoLoginRewardTask")
		client.ensureInternalSession()
		val autoClaimEntries = r.table("auto_claim").run(client.dbConn, AutoClaimEntry::class.java).shuffled(random)
		val users = client.internalSession.queryUsersMap(autoClaimEntries.map { it.id })
		for (entry in autoClaimEntries) {
			var attempts = 5
			while (attempts-- > 0) {
				logger.info("Performing auto claiming for account ${entry.id}, attempt ${5 - attempts}")
				if (perform(entry, users)) {
					break
				}
			}
			Thread.sleep(2500L + random.nextInt(2500))
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
				source.complete("Disabled automatic daily rewards claiming of `$epicId` because that account has been deleted or deactivated.")
				return true
			}
			val savedDevice = client.savedLoginsManager.get(discordId, epicId)
			if (savedDevice == null) {
				disableAutoClaim(epicId)
				source.complete("Disabled automatic daily rewards claiming of `$displayName` because we couldn't find a saved login.")
				return true
			}
			withDevice(source, savedDevice, { session ->
				session.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "campaign").await()
				val dailyRewardStat = (source.api.profileManager.getProfileData("campaign").stats as CampaignProfileStats).daily_rewards
				val millisInDay = 24L * 60L * 60L * 1000L
				if (dailyRewardStat?.lastClaimDate?.time?.let { it / millisInDay == System.currentTimeMillis() / millisInDay } != false) {
					return@withDevice
				}
				val response = session.api.profileManager.dispatchClientCommandRequest(ClaimLoginReward(), "campaign").await()
				notifyDailyRewardsClaimed(
					source,
					source.api.profileManager.getProfileData("campaign"),
					response.notifications.filterIsInstance<DailyRewardsNotification>().firstOrNull()
				)
			}, { disableAutoClaim(epicId) }, users)
			return true
		} catch (e: ErrorResponseException) {
			client.dlog("Failed to claim dailies for ${displayName ?: epicId} (registered by <@$discordId>)\n$e", null)
			logger.warn("Failed to claim dailies for ${entry.id}", e)
			return true
		} catch (e: IOException) {
			client.dlog("Failed to claim dailies for ${displayName ?: epicId} (registered by <@$discordId>). Retrying\n$e", null)
			logger.warn("Failed to claim dailies for ${entry.id}. Retrying", e)
			return false
		}
	}

	private fun disableAutoClaim(accountId: String) {
		r.table("auto_claim").get(accountId).delete().run(client.dbConn)
	}
}