package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.renderWithIcon
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.ClaimLoginReward
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin
import com.tb24.fn.model.mcpprofile.notifications.DailyRewardsNotification
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition
import me.fungames.jfortniteparse.fort.objects.rows.FortLoginReward
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass

val defaultScheduleRewards by lazy { loadObject<UDataTable>("/SaveTheWorld/Balance/DataTables/DailyRewards")!!.rows.map { it.value.mapToClass(FortLoginReward::class.java) } }

class DailyRewardsCommand : BrigadierCommand("daily", "Claims the STW daily reward.", arrayOf("dailyrewards", "claimdaily", "d", "claim")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Claiming daily rewards")
		source.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "campaign").await()
		return try {
			val response = source.api.profileManager.dispatchClientCommandRequest(ClaimLoginReward(), "campaign").await()
			val campaign = source.api.profileManager.getProfileData("campaign")
			notifyDailyRewardsClaimed(source, campaign, response.notifications.filterIsInstance<DailyRewardsNotification>().firstOrNull())
			Command.SINGLE_SUCCESS
		} catch (e: HttpException) {
			if (e.epicError.errorCode == "errors.com.epicgames.fortnite.check_access_failed") {
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "common_core").await()
				val msg = if (source.api.profileManager.getProfileData("common_core").items.values.none { it.templateId == "Token:campaignaccess" }) {
					"You don't have access to Save the World."
				} else {
					"You have access to STW, but you can start receiving daily rewards after completing Stonewood Storm Shield Defense 3."
				}
				source.complete(null, source.createEmbed().setColor(COLOR_ERROR)
					.setDescription("❌ $msg")
					.build())
				0
			} else throw e
		}
	}
}

fun notifyDailyRewardsClaimed(source: CommandSourceStack, campaign: McpProfile, notification: DailyRewardsNotification?) {
	val daysLoggedIn = (campaign.stats as CampaignProfileStats).daily_rewards?.totalDaysLoggedIn
		?: throw SimpleCommandExceptionType(LiteralMessage("Daily rewards data not found.")).create()
	val canReceiveMtxCurrency = campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
	val claimedIndex = daysLoggedIn - 1
	val todaysReward = defaultScheduleRewards[claimedIndex % defaultScheduleRewards.size]
	val todaysRewardItem = todaysReward.asItemStack().apply { setConditionForConditionalItem(canReceiveMtxCurrency) }
	val preview = mutableListOf<String>()
	repeat(7) {
		val currentIndex = claimedIndex + it
		val reward = defaultScheduleRewards[currentIndex % defaultScheduleRewards.size]
		preview.add(reward.render(currentIndex, claimedIndex, canReceiveMtxCurrency))
	}
	val embed = source.createEmbed()
		.setThumbnail(Utils.benBotExportAsset(todaysRewardItem.getPreviewImagePath(true)?.toString()))
		.setDescription("**%,d** days logged in".format(daysLoggedIn))
		.addField("Rewards", preview.joinToString("\n"), true)
	var currentIndex = daysLoggedIn // start from next day (claimedIndex + 1)
	while (true) {
		val entry = defaultScheduleRewards[currentIndex % defaultScheduleRewards.size]
		if (entry.bIsMajorReward && (!entry.ItemDefinition.toString().endsWith("Currency_MtxSwap") || entry.ItemCount >= 300)) {
			embed.addField("Next Epic reward", entry.render(currentIndex, claimedIndex, canReceiveMtxCurrency), true)
			break
		}
		++currentIndex
	}
	if (notification?.items?.isNotEmpty() == true) {
		embed.setColor(BrigadierCommand.COLOR_SUCCESS).setTitle("✅ Daily rewards claimed")
	} else {
		embed.setTitle("Daily rewards already claimed")
	}
	source.complete(null, embed.build())
}

private fun FortLoginReward.render(currentIndex: Int, claimedIndex: Int, canReceiveMtxCurrency: Boolean): String {
	val renderedItem = asItemStack().apply { setConditionForConditionalItem(canReceiveMtxCurrency) }.renderWithIcon(bypassWhitelist = true)
	val s = "`%d` %s".format(currentIndex + 1, if (bIsMajorReward) "**$renderedItem**" else renderedItem)
	return if (currentIndex <= claimedIndex) "~~$s~~" else s
}

private fun FortLoginReward.asItemStack() = FortItemStack(ItemDefinition.load<FortItemDefinition>(), ItemCount)