package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.attributes.CampaignProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.ClaimLoginReward
import com.tb24.fn.model.mcpprofile.notifications.DailyRewardsNotification
import com.tb24.fn.util.Formatters

class DailyRewardsCommand : BrigadierCommand("dailyrewards", "Claims the STW daily reward.", arrayListOf("daily", "claimdaily")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Claiming daily rewards")
			val response = source.api.profileManager.dispatchClientCommandRequest(ClaimLoginReward(), "campaign").await()
			val campaign = source.api.profileManager.getProfileData("campaign")
			val attrs = campaign.stats.attributes as CampaignProfileAttributes
			val notification = response.notifications.filterIsInstance<DailyRewardsNotification>().firstOrNull()
			val item = notification?.items?.firstOrNull()
			source.complete(null, if (item != null) source.createEmbed()
				.setTitle("Daily Reward Claimed")
				.setColor(0x40FAA1)
				.addField("Days logged in", Formatters.num.format(notification.daysLoggedIn), true)
				.addField("Reward", "${Formatters.num.format(item.quantity)} \u00d7 ${item.itemType.replace("MtxComplimentary", Utils.MTX_EMOJI)}", true)
				.build()
			else source.createEmbed()
				.setTitle("Already Claimed")
				.setColor(0x40FAA1)
				.addField("Days logged in", Formatters.num.format(attrs.daily_rewards?.totalDaysLoggedIn ?: 0), true)
				.build())
			Command.SINGLE_SUCCESS
		}
}