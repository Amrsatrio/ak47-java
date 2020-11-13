package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.Rune
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.render
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.AthenaDailyQuestDefinition
import com.tb24.fn.model.assetdata.FortQuestItemDefinition
import com.tb24.fn.model.assetdata.FortQuestItemDefinition.EFortQuestType
import com.tb24.fn.model.assetdata.FortQuestRewardTableRow
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass

class AthenaDailyChallengesCommand : BrigadierCommand("dailychallenges", "Shows your active BR daily challenges.", arrayOf("dailychals")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::hasAssetsLoaded)
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting challenges")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			source.complete(null, source.createEmbed()
				.setTitle("Quick Challenges")
				.setDescription(source.api.profileManager.getProfileData("athena").items.values
					.filter { it.primaryAssetType == "Quest" && it.defData is AthenaDailyQuestDefinition && it.attributes["quest_state"]?.asString == "Active" }
					.joinToString("\n") { renderChallenge(it, true) }.takeIf { it.isNotEmpty() } ?: "You have no quick challenges")
				.build())
			Command.SINGLE_SUCCESS
		}
}

class DailyQuestsCommand : BrigadierCommand("dailyquests", "Shows your active STW daily quests.", arrayOf("dailies")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::hasAssetsLoaded)
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting quests")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
			source.complete(null, source.createEmbed()
				.setTitle("Daily Quests")
				.setDescription(source.api.profileManager.getProfileData("campaign").items.values
					.filter { it.primaryAssetType == "Quest" && (it.defData as? FortQuestItemDefinition)?.QuestType == EFortQuestType.DailyQuest && it.attributes["quest_state"]?.asString == "Active" }
					.joinToString("\n", transform = ::renderChallenge).takeIf { it.isNotEmpty() } ?: "You have no active daily quests")
				.build())
			Command.SINGLE_SUCCESS
		}
}

fun renderChallenge(item: FortItemStack, canBold: Boolean = false): String {
	val quest = item.defData as FortQuestItemDefinition
	var completion = 0
	var max = 0
	for (objective in quest.Objectives) {
		if (objective.bHidden) {
			continue
		}
		completion += Utils.getCompletion(objective, item)
		max += objective.Count
	}
	if (quest.ObjectiveCompletionCount != null) {
		max = quest.ObjectiveCompletionCount
	}
	val xpRewardScalar = item.attributes["xp_reward_scalar"]?.asFloat ?: 1f
	val sb = StringBuilder("• **%s** ( %,d / %,d )".format(item.displayName, completion, max))
	val rewards = quest.RewardsTable?.rows
		?.mapValues { it.value.mapToClass(FortQuestRewardTableRow::class.java) }
		?.filter { it.value.QuestTemplateId == "*" || it.value.QuestTemplateId == item.templateId && !it.value.Hidden }
	if (rewards != null && rewards.isNotEmpty()) {
		sb.append('\n').append(rewards.render("\n\u00a0\u00a0\u00a0", xpRewardScalar, canBold && xpRewardScalar == 1f))
	}
	return sb.toString()
}
