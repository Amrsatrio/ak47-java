package com.tb24.discordbot.commands

import com.tb24.discordbot.webcampaign.WebCampaign

class LanternTrialsCommand : WebCampaignCommand("lanterntrials", "Lantern Trials", "lanterntrials", arrayOf("lt")) {
	override fun getRewardTitle(webCampaign: WebCampaign, reward: WebCampaign.WebCampaignReward) =
		if (reward.index == 7) {
			"Reward for unlocking two stretch goals during the challenge period"
		} else {
			super.getRewardTitle(webCampaign, reward)
		}
}