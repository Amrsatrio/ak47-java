package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.renderWithIcon
import com.tb24.fn.model.mcpprofile.attributes.CampaignProfileAttributes
import com.tb24.fn.model.mcpprofile.attributes.ProfileAttributes.FortDailyLoginRewardStat
import com.tb24.fn.model.mcpprofile.commands.campaign.ClaimLoginReward
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin
import com.tb24.fn.model.mcpprofile.notifications.DailyRewardsNotification
import com.tb24.fn.util.Formatters

class DailyRewardsCommand : BrigadierCommand("dailyrewards", "Claims the STW daily reward.", arrayOf("daily", "claimdaily", "d", "claim")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Claiming daily rewards")
			source.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "campaign").await()
			val response = source.api.profileManager.dispatchClientCommandRequest(ClaimLoginReward(), "campaign").await()
			val campaign = source.api.profileManager.getProfileData("campaign")
			notifyDailyRewardsClaimed(source,
				(campaign.stats.attributes as CampaignProfileAttributes).daily_rewards,
				response.notifications.filterIsInstance<DailyRewardsNotification>().firstOrNull())
			Command.SINGLE_SUCCESS
		}
}

fun notifyDailyRewardsClaimed(source: CommandSourceStack, stat: FortDailyLoginRewardStat?, notification: DailyRewardsNotification?) {
	val item = notification?.items?.firstOrNull()
	val daysLoggedIn = Formatters.num.format(stat?.totalDaysLoggedIn ?: 0)
	source.complete(null, if (item != null) source.createEmbed().setColor(BrigadierCommand.COLOR_SUCCESS)
		.setTitle("✅ Daily rewards claimed")
		.addField("Days logged in", daysLoggedIn, true)
		.addField("Reward", item.asItemStack().renderWithIcon(), true)
		.build()
	else source.createEmbed()
		.setTitle("Daily rewards already claimed")
		.addField("Days logged in", daysLoggedIn, true)
		.build())
} // TODO since you got assets working, please make a comprehensive rewards info