package com.tb24.discordbot.tasks

import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.commands.notifyDailyRewardsClaimed
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.campaign.ClaimLoginReward
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin
import com.tb24.fn.model.mcpprofile.notifications.DailyRewardsNotification
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats

class AutoLoginRewardTask(client: DiscordBot) : AbstractAutoTask(client, "auto_claim") {
	override fun performForAccount(source: CommandSourceStack): Int {
		source.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "campaign").await()
		val dailyRewardStat = (source.api.profileManager.getProfileData("campaign").stats as CampaignProfileStats).daily_rewards
		val millisInDay = 24L * 60L * 60L * 1000L
		if (dailyRewardStat?.lastClaimDate?.time?.let { it / millisInDay == System.currentTimeMillis() / millisInDay } != false) {
			return 0
		}
		val response = source.api.profileManager.dispatchClientCommandRequest(ClaimLoginReward(), "campaign").await()
		notifyDailyRewardsClaimed(
			source,
			source.api.profileManager.getProfileData("campaign"),
			response.notifications.filterIsInstance<DailyRewardsNotification>().firstOrNull()
		)
		return 1
	}

	override fun text1() = "auto daily rewards claiming"
	override fun text2() = "claim daily rewards"
}