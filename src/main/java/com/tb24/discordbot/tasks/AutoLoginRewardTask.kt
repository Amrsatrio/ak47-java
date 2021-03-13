package com.tb24.discordbot.tasks

import com.google.common.collect.ImmutableMap
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.Session
import com.tb24.discordbot.commands.CommandSourceStack
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
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.internal.entities.UserImpl
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
		val autoClaimEntries = r.table("auto_claim").run(client.dbConn, AutoClaimEntry::class.java).shuffled(random)
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
		TASK_IS_RUNNING.set(false)
	}

	private fun perform(entry: AutoClaimEntry): Boolean {
		val epicId = entry.id
		val discordId = entry.registrantId
		var source: CommandSourceStack? = null
		var displayName: String? = null
		try {
			try {
				displayName = client.internalSession.queryUsers(Collections.singleton(epicId)).firstOrNull()?.displayName
				val user = client.discord.getUserById(discordId) ?: client.discord.retrieveUserById(discordId).complete()
				val channel = (user as UserImpl).privateChannel ?: user.openPrivateChannel().complete()
				source = PrivateChannelCommandSource(client, channel)
				if (displayName == null) {
					disableAutoClaim(epicId)
					source.complete("Disabled automatic daily rewards claiming of `$epicId` because that account has been deleted or deactivated.")
					return true
				}
				var session = client.sessions[discordId]
				var requiresLogin = false
				if (session?.api?.currentLoggedIn?.id != epicId) {
					session = Session(client, discordId, false)
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
						disableAutoClaim(epicId)
						source.complete("Disabled automatic daily rewards claiming of `$displayName` because we couldn't find a saved login.")
						return true
					}
					session.login(source, GrantType.device_auth, ImmutableMap.of("account_id", savedDevice.accountId, "device_id", savedDevice.deviceId, "secret", savedDevice.secret, "token_type", "eg1"), savedDevice.clientId?.let(EAuthClient::getByClientId) ?: EAuthClient.FORTNITE_IOS_GAME_CLIENT, false)
				}
				session.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "campaign").await()
				val dailyRewardStat = (source.api.profileManager.getProfileData("campaign").stats.attributes as CampaignProfileAttributes).daily_rewards
				val millisInDay = 24L * 60L * 60L * 1000L
				if (dailyRewardStat?.lastClaimDate?.time?.let { it / millisInDay == System.currentTimeMillis() / millisInDay } != false) {
					//if (user.idLong == 624299014388711455L) notifyDailyRewardsClaimed(source, dailyRewardStat, null)
					return true
				}
				val response = session.api.profileManager.dispatchClientCommandRequest(ClaimLoginReward(), "campaign").await()
				notifyDailyRewardsClaimed(
					source,
					(source.api.profileManager.getProfileData("campaign").stats.attributes as CampaignProfileAttributes).daily_rewards,
					response.notifications.filterIsInstance<DailyRewardsNotification>().firstOrNull()
				)
				if (requiresLogin) {
					source.session.logout(null)
				}
				return true
			} catch (e: HttpException) {
				if (e.epicError.errorCode == "errors.com.epicgames.account.invalid_account_credentials" || e.epicError.errorCode == "errors.com.epicgames.account.account_not_active") {
					disableAutoClaim(epicId)
					client.savedLoginsManager.remove(discordId, epicId)
					source?.complete("Disabled automatic daily rewards claiming of `${displayName ?: epicId}` because the saved login is no longer valid.")
					return true
				}
				if (source != null) client.commandManager.httpError(source, e, "Failed to automatically claim daily rewards")
				client.dlog("Failed to claim dailies for ${displayName ?: epicId} (registered by <@$discordId>)\n$e", null)
				logger.warn("Failed to claim dailies for ${entry.id}", e)
				return true
			}
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

class AutoClaimEntry(@JvmField var id: String, @JvmField var registrantId: String) {
	constructor() : this("", "")
}