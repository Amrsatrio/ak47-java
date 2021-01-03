package com.tb24.discordbot

import com.google.common.collect.ImmutableMap
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
import java.util.*

class AutoLoginRewardTask(val client: DiscordBot) : Runnable {
	val random = Random()

	override fun run() {
		val autoClaimEntries = r.table("auto_claim").run(client.dbConn, AutoClaimEntry::class.java)
		while (autoClaimEntries.hasNext()) {
			val entry = autoClaimEntries.next()!!
			val epicId = entry.accountId
			val displayName = client.internalSession.queryUsers(setOf(epicId)).first().displayName // TODO what if the account is deleted
			val discordId = entry.registrantId
			val user = client.discord.getUserById(discordId) ?: client.discord.retrieveUserById(discordId).complete()
			val channel = (user as UserImpl).privateChannel ?: user.openPrivateChannel().complete()
			val source = PrivateChannelCommandSource(client, channel)
			var session = client.sessions[discordId]
			var requiresLogin = false
			if (session == null) {
				session = Session(client, discordId)
				requiresLogin = true
			} else {
				val verifyResponse = session.api.accountService.verify(null).execute()
				if (verifyResponse.code() == 401) {
					requiresLogin = true
				} else if (verifyResponse.code() != 200) {
					source.complete("API error occurred whilst trying to login to automatically claim the daily rewards of `$displayName`:\n${EpicError.parse(verifyResponse).displayText}")
					continue
				}
			}
			source.session = session
			if (requiresLogin) {
				val savedDevice = client.savedLoginsManager.get(discordId, epicId)
				if (savedDevice == null) {
					removeFromDb(epicId)
					source.complete("We attempted to automatically claim the daily rewards of `$displayName` but we couldn't find a saved login. As a result, we've unregistered that account from the list.")
					continue
				}
				session.login(source, GrantType.device_auth, ImmutableMap.of("account_id", savedDevice.accountId, "device_id", savedDevice.deviceId, "secret", savedDevice.secret, "token_type", "eg1"), savedDevice.clientId?.let { EAuthClient.getByClientId(it) } ?: EAuthClient.FORTNITE_IOS_GAME_CLIENT)
			}
			session.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "campaign").await()
			val dailyRewardStat = (source.api.profileManager.getProfileData("campaign").stats.attributes as CampaignProfileAttributes).daily_rewards
			val millisInDays = 24L * 60L * 60L * 1000L
			if (dailyRewardStat?.lastClaimDate?.time?.let { it / millisInDays == System.currentTimeMillis() / millisInDays } != false) {
				notifyDailyRewardsClaimed(source, dailyRewardStat, null)
				continue
			}
			val response = session.api.profileManager.dispatchClientCommandRequest(ClaimLoginReward(), "campaign").await()
			notifyDailyRewardsClaimed(source,
				(source.api.profileManager.getProfileData("campaign").stats.attributes as CampaignProfileAttributes).daily_rewards,
				response.notifications.filterIsInstance<DailyRewardsNotification>().firstOrNull())
			if (requiresLogin) {
				source.session.logout(null)
			}
			Thread.sleep(2500L + random.nextInt(2500))
		}
	}

	fun removeFromDb(accountId: String) {
		r.table("auto_claim").get(accountId).delete().run(client.dbConn)
	}

	class AutoClaimEntry(@JvmField var accountId: String, @JvmField var registrantId: String)
}