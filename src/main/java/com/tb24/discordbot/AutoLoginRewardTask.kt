package com.tb24.discordbot

import com.google.common.collect.ImmutableMap
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.commands.GrantType
import com.tb24.discordbot.commands.PrivateChannelCommandSource
import com.tb24.discordbot.commands.notifyDailyRewardsClaimed
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.EpicError
import com.tb24.fn.model.mcpprofile.attributes.CampaignProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.campaign.ClaimLoginReward
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin
import com.tb24.fn.model.mcpprofile.notifications.DailyRewardsNotification
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.internal.entities.UserImpl
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

val taskIsRunning = AtomicBoolean()

class AutoLoginRewardTask(val client: DiscordBot) : Runnable {
	val logger = LoggerFactory.getLogger("AutoLoginReward")
	val random = Random()

	override fun run() {
		if (taskIsRunning.get()) {
			throw SimpleCommandExceptionType(LiteralMessage("Task is already running.")).create()
		}
		taskIsRunning.set(true)
		val autoClaimEntries = r.table("auto_claim").run(client.dbConn, AutoClaimEntry::class.java)
		for (entry in autoClaimEntries) {
			var attempts = 5
			while (attempts-- > 0) {
				logger.info("Performing auto claiming for account ${entry.id}, attempt ${5 - attempts}")
				if (perform(entry)) {
					break
				}
			}
			Thread.sleep(2500L + random.nextInt(2500))
		}
	}

	private fun perform(entry: AutoClaimEntry): Boolean {
		val epicId = entry.id
		val displayName = client.internalSession.queryUsers(setOf(epicId)).first().displayName // TODO what if the account is deleted
		val discordId = entry.registrantId
		val user = client.discord.getUserById(discordId) ?: client.discord.retrieveUserById(discordId).complete()
		val channel = (user as UserImpl).privateChannel ?: user.openPrivateChannel().complete()
		val source = PrivateChannelCommandSource(client, channel)
		var session = client.sessions[discordId]
		var requiresLogin = false
		try {
			if (session == null) {
				session = Session(client, discordId)
				requiresLogin = true
			} else {
				val verifyResponse = session.api.accountService.verify(null).execute()
				if (verifyResponse.code() == 401) {
					requiresLogin = true
				} else if (verifyResponse.code() != 200) {
					source.complete("API error occurred whilst trying to login to automatically claim the daily rewards of `$displayName`:\n${EpicError.parse(verifyResponse).displayText}")
					return true
				}
			}
			source.session = session
			if (requiresLogin) {
				val savedDevice = client.savedLoginsManager.get(discordId, epicId)
				if (savedDevice == null) {
					removeFromDb(epicId)
					source.complete("We attempted to automatically claim the daily rewards of `$displayName` but we couldn't find a saved login. As a result, we've unregistered that account from the list.")
					return true
				}
				session.login(source, GrantType.device_auth, ImmutableMap.of("account_id", savedDevice.accountId, "device_id", savedDevice.deviceId, "secret", savedDevice.secret, "token_type", "eg1"), savedDevice.clientId?.let { EAuthClient.getByClientId(it) } ?: EAuthClient.FORTNITE_IOS_GAME_CLIENT, false)
			}
			session.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "campaign").await()
			val dailyRewardStat = (source.api.profileManager.getProfileData("campaign").stats.attributes as CampaignProfileAttributes).daily_rewards
			val millisInDay = 24L * 60L * 60L * 1000L
			if (dailyRewardStat?.lastClaimDate?.time?.let { it / millisInDay == System.currentTimeMillis() / millisInDay } != false) {
				//notifyDailyRewardsClaimed(source, dailyRewardStat, null)
				return true
			}
			val response = session.api.profileManager.dispatchClientCommandRequest(ClaimLoginReward(), "campaign").await()
			notifyDailyRewardsClaimed(source,
				(source.api.profileManager.getProfileData("campaign").stats.attributes as CampaignProfileAttributes).daily_rewards,
				response.notifications.filterIsInstance<DailyRewardsNotification>().firstOrNull())
			if (requiresLogin) {
				source.session.logout(null)
			}
			return true
		} catch (e: HttpException) {
			source.client.commandManager.httpError(source, e, "Failed to automatically claim daily rewards")
			return true
		} catch (e: IOException) {
			logger.warn("Failed to claim dailies for $entry.id", e)
			return false
		}
	}

	fun removeFromDb(accountId: String) {
		r.table("auto_claim").get(accountId).delete().run(client.dbConn)
	}
}

class AutoClaimEntry(@JvmField var id: String, @JvmField var registrantId: String) {
	constructor() : this("", "")
}