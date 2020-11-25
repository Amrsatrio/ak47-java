package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.Rune
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.attributes.IQuestManager
import com.tb24.fn.model.mcpprofile.commands.ClientQuestLogin
import com.tb24.fn.model.mcpprofile.commands.FortRerollDailyQuest
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.Formatters
import me.fungames.jfortniteparse.fort.exports.AthenaDailyQuestDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition.EFortQuestType
import me.fungames.jfortniteparse.fort.objects.rows.FortQuestRewardTableRow
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass


class AthenaDailyChallengesCommand : BrigadierCommand("dailychallenges", "Manages your active BR daily challenges.", arrayOf("dailychals")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::hasAssetsLoaded)
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting challenges")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			source.complete(null, source.createEmbed()
				.setTitle("Quick Challenges")
				.setDescription(getAthenaDailyQuests(athena)
					.joinToString("\n") { renderChallenge(it, "• ", "\u00a0\u00a0\u00a0", true) }
					.takeIf { it.isNotEmpty() } ?: "You have no quick challenges")
				.addField("Rerolls remaining", Formatters.num.format((athena.stats.attributes as IQuestManager).questManager?.dailyQuestRerolls ?: 0), false)
				.setColor(0x40FAA1)
				.build())
			Command.SINGLE_SUCCESS
		}
		.then(literal("replace")
			.then(argument("quick challenge #", integer())
				.executes { c -> replaceQuest(c.source, "campaign", getInteger(c, "quick challenge #")) { getAthenaDailyQuests(it) } }
			)
		)

	private fun getAthenaDailyQuests(athena: McpProfile) =
		athena.items.values
			.filter { it.primaryAssetType == "Quest" && it.defData is AthenaDailyQuestDefinition && it.attributes["quest_state"]?.asString == "Active" }
			.sortedBy { it.displayName }
}

class DailyQuestsCommand : BrigadierCommand("dailyquests", "Manages your active STW daily quests.", arrayOf("dailies")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::hasAssetsLoaded)
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting quests")
			source.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "campaign").await()
			val campaign = source.api.profileManager.getProfileData("campaign")
			val canReceiveMtxCurrency = campaign.items.values.firstOrNull { it.templateId == "Token:receivemtxcurrency" } != null
			source.complete(null, source.createEmbed()
				.setTitle("Daily Quests")
				.setDescription(getCampaignDailyQuests(campaign)
					.mapIndexed { i, it -> renderChallenge(it, "${i + 1}. ", "\u00a0\u00a0\u00a0", conditionalCondition = canReceiveMtxCurrency) }
					.joinToString("\n")
					.takeIf { it.isNotEmpty() } ?: "You have no active daily quests")
				.addField("Rerolls remaining", Formatters.num.format((campaign.stats.attributes as IQuestManager).questManager?.dailyQuestRerolls ?: 0), false)
				.setColor(0x40FAA1)
				.build())
			Command.SINGLE_SUCCESS
		}
		.then(literal("replace")
			.then(argument("daily quest #", integer())
				.executes { c -> replaceQuest(c.source, "campaign", getInteger(c, "daily quest #")) { getCampaignDailyQuests(it) } }
			)
		)

	private fun getCampaignDailyQuests(campaign: McpProfile) =
		campaign.items.values
			.filter { it.primaryAssetType == "Quest" && (it.defData as? FortQuestItemDefinition)?.QuestType == EFortQuestType.DailyQuest && it.attributes["quest_state"]?.asString == "Active" }
			.sortedBy { it.displayName }
}

fun replaceQuest(source: CommandSourceStack, profileId: String, questIndex: Int, questsGetter: (McpProfile) -> List<FortItemStack>): Int {
	source.ensureSession()
	source.loading("Getting quests")
	source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
	var profile = source.api.profileManager.getProfileData(profileId)
	val canReceiveMtxCurrency = profile.items.values.firstOrNull { it.templateId == "Token:receivemtxcurrency" } != null
	val currentDailies = questsGetter(profile)
	val questToReplace = currentDailies.getOrNull(questIndex - 1)
		?: throw SimpleCommandExceptionType(LiteralMessage("Invalid daily quest number.")).create()
	val remainingRerolls = (profile.stats.attributes as IQuestManager).questManager?.dailyQuestRerolls ?: 0
	if (remainingRerolls <= 0) {
		throw SimpleCommandExceptionType(LiteralMessage("You ran out of daily quest rerolls for today.")).create()
	}
	if (!source.complete(null, source.createEmbed()
			.setTitle("Replace this daily quest?")
			.setDescription(renderChallenge(questToReplace, conditionalCondition = canReceiveMtxCurrency))
			.setColor(0xFFF300)
			.build()).yesNoReactions(source.author).await()) {
		source.complete("👌 Alright.")
		return Command.SINGLE_SUCCESS
	}
	source.loading("Replacing daily quest")
	source.api.profileManager.dispatchClientCommandRequest(FortRerollDailyQuest().apply { questId = questToReplace.itemId }, profileId)
	profile = source.api.profileManager.getProfileData(profileId)
	source.complete(null, source.createEmbed()
		.setTitle("✅ Replaced a daily quest")
		.setDescription("Here's your daily quests now:")
		.addField("Daily Quests", questsGetter(profile)
			.mapIndexed { i, it -> renderChallenge(it, "${i + 1}. ", "\u00a0\u00a0\u00a0", conditionalCondition = canReceiveMtxCurrency) }
			.joinToString("\n")
			.takeIf { it.isNotEmpty() } ?: "You have no active daily quests", false)
		.setColor(0x40FAA1)
		.build())
	return Command.SINGLE_SUCCESS
}

fun renderChallenge(item: FortItemStack, prefix: String = "", rewardsPrefix: String = "", canBold: Boolean = false, conditionalCondition: Boolean = false): String {
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
		sb.append('\n').append(rewards.render(rewardsPrefix, xpRewardScalar, canBold && xpRewardScalar == 1f, conditionalCondition))
	}
	return sb.toString()
}
