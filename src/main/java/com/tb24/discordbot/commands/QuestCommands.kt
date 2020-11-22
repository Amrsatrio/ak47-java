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
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import me.fungames.jfortniteparse.fort.exports.AthenaDailyQuestDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition.EFortQuestType
import me.fungames.jfortniteparse.fort.objects.rows.FortQuestRewardTableRow
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
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
					.sortedBy { it.displayName }
					.joinToString("\n") { renderChallenge(it, "• ", true) }
					.takeIf { it.isNotEmpty() } ?: "You have no quick challenges")
				.setColor(0x40FAA1)
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
			val campaignItems = source.api.profileManager.getProfileData("campaign").items
			val canReceiveMtxCurrency = campaignItems.values.firstOrNull { it.templateId == "Token:receivemtxcurrency" } != null
			source.complete(null, source.createEmbed()
				.setTitle("Daily Quests")
				.setDescription(campaignItems.values
					.filter { it.primaryAssetType == "Quest" && (it.defData as? FortQuestItemDefinition)?.QuestType == EFortQuestType.DailyQuest && it.attributes["quest_state"]?.asString == "Active" }
					.sortedBy { it.displayName }
					.mapIndexed { i, it -> renderChallenge(it, "${i + 1}. ", conditionalCondition = canReceiveMtxCurrency) }
					.joinToString("\n")
					.takeIf { it.isNotEmpty() } ?: "You have no active daily quests")
				.setColor(0x40FAA1)
				.build())
			Command.SINGLE_SUCCESS
		}
	/*.then(literal("replace")
		.then(argument("daily quest #", integer())
			.executes { c ->
				val num = getInteger(c, "daily quest #")
				val source = c.source
				source.ensureSession()
				source.loading("Replacing daily quest")
				source.api.profileManager.dispatchClientCommandRequest(FortRerollDailyQuest(), "campaign")
				source.complete(null, source.createEmbed()
					.setTitle("✅ Replaced daily quest")
					.addField("Before", "TODO", false)
					.addField("After", "TODO", false)
					.setColor(0x40FAA1)
					.build())
				Command.SINGLE_SUCCESS
			}
		)
	)*/
}

fun renderChallenge(item: FortItemStack, prefix: String, canBold: Boolean = false, conditionalCondition: Boolean = false): String {
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
	val sb = StringBuilder("%s**%s** ( %,d / %,d )".format(prefix, item.displayName, completion, max))
	val rewards = quest.RewardsTable?.load<UDataTable>()?.rows
		?.mapValues { it.value.mapToClass(FortQuestRewardTableRow::class.java) }
		?.filter { it.value.QuestTemplateId == "*" || it.value.QuestTemplateId == item.templateId && !it.value.Hidden }
	if (rewards != null && rewards.isNotEmpty()) {
		sb.append('\n').append(rewards.render("\u00a0\u00a0\u00a0", xpRewardScalar, canBold && xpRewardScalar == 1f, conditionalCondition))
	}
	return sb.toString()
}
