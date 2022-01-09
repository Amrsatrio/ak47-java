package com.tb24.discordbot.commands

import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.Rune
import com.tb24.discordbot.commands.arguments.ItemArgument.Companion.getItem
import com.tb24.discordbot.commands.arguments.ItemArgument.Companion.item
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.assetdata.RewardCategoryTabData
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.subgame.FortRerollDailyQuest
import com.tb24.fn.model.mcpprofile.stats.IQuestManager
import com.tb24.fn.util.format
import com.tb24.uasset.getProp
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.fort.exports.AthenaDailyQuestDefinition
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition.EFortQuestType
import me.fungames.jfortniteparse.fort.exports.FortTandemCharacterData
import me.fungames.jfortniteparse.fort.objects.rows.FortQuestRewardTableRow
import me.fungames.jfortniteparse.ue4.assets.exports.UObject
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTagContainer
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Message

class AthenaDailyChallengesCommand : BrigadierCommand("dailychallenges", "Manages your active BR daily challenges.", arrayOf("dailychals", "brdailies", "bd")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting challenges")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val numRerolls = (athena.stats as IQuestManager).questManager?.dailyQuestRerolls ?: 0
			var description = getAthenaDailyQuests(athena)
				.mapIndexed { i, it -> renderChallenge(it, "${i + 1}. ", "\u00a0\u00a0\u00a0", isAthenaDaily = true) }
				.joinToString("\n")
			if (description.isEmpty()) {
				description = "You have no daily challenges"
			} else if (numRerolls > 0 && Rune.isBotDev(source)) {
				description += "\n\n" + "You have %,d reroll(s) remaining today.\nUse `%s%s replace <%s>` to replace one."
					.format(numRerolls, source.prefix, c.commandName, "daily challenge #")
			}
			source.complete(null, source.createEmbed()
				.setTitle("Daily Challenges")
				.setDescription(description)
				.build())
			Command.SINGLE_SUCCESS
		}
		.then(literal("replace")
			.executes { replaceQuest(it.source, "athena", -1, ::getAthenaDailyQuests) }
			.then(argument("daily challenge #", integer())
				.executes { replaceQuest(it.source, "athena", getInteger(it, "daily challenge #"), ::getAthenaDailyQuests) }
			)
		)

	private fun getAthenaDailyQuests(athena: McpProfile) =
		athena.items.values
			.filter { it.primaryAssetType == "Quest" && it.attributes["quest_state"]?.asString == "Active" && it.defData is AthenaDailyQuestDefinition }
			.sortedBy { it.displayName }
}

class DailyQuestsCommand : BrigadierCommand("dailyquests", "Manages your active STW daily quests.", arrayOf("dailies", "stwdailies", "dq")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::displayDailyQuests, "Getting quests")
		.then(literal("replace")
			.then(argument("daily quest #", integer())
				.executes { replaceQuest(it.source, "campaign", getInteger(it, "daily quest #"), ::getCampaignDailyQuests) }
			)
		)
		.then(literal("bulk")
			.executes { executeBulk(it.source) }
			.then(argument("users", UserArgument.users(15))
				.executes { executeBulk(it.source, lazy { UserArgument.getUsers(it, "users").values }) }
			)
		)

	private fun displayDailyQuests(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		val canReceiveMtxCurrency = campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
		val numRerolls = (campaign.stats as IQuestManager).questManager?.dailyQuestRerolls ?: 0
		var description = getCampaignDailyQuests(campaign)
			.mapIndexed { i, it -> renderChallenge(it, "${i + 1}. ", "\u00a0\u00a0\u00a0", conditionalCondition = canReceiveMtxCurrency) }
			.joinToString("\n")
		if (description.isEmpty()) {
			description = "You have no active daily quests"
		} else if (campaign.owner == source.api.currentLoggedIn && numRerolls > 0) {
			description += "\n\n" + "You have %,d reroll(s) remaining today.\nUse `%s%s replace <%s>` to replace one."
				.format(numRerolls, source.prefix, c.commandName, "daily quest #")
		}
		source.complete(null, source.createEmbed(campaign.owner)
			.setTitle("Daily Quests")
			.setDescription(description)
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun getCampaignDailyQuests(campaign: McpProfile) =
		campaign.items.values
			.filter { it.primaryAssetType == "Quest" && it.attributes["quest_state"]?.asString == "Active" && (it.defData as? FortQuestItemDefinition)?.QuestType == EFortQuestType.DailyQuest }
			.sortedBy { it.displayName }

	private fun executeBulk(source: CommandSourceStack, usersLazy: Lazy<Collection<GameProfile>>? = null): Int {
		source.conditionalUseInternalSession()
		val entries = stwBulk(source, usersLazy) { campaign ->
			val completedTutorial = (campaign.items.values.firstOrNull { it.templateId == "Quest:outpostquest_t1_l3" }?.attributes?.get("completion_complete_outpost_1_3")?.asInt ?: 0) > 0
			if (!completedTutorial) return@stwBulk null

			val quests = getCampaignDailyQuests(campaign).joinToString("\n") { renderChallenge(it, "\u2800", null, allowBold = false) }
			campaign.owner.displayName to quests.ifEmpty { "\u2800✅ All dailies completed!" }
		}
		if (entries.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("All users we're trying to display aren't eligible to do daily quests.")).create()
		}
		val embed = EmbedBuilder().setColor(COLOR_INFO)
		for (entry in entries) {
			embed.addField(entry.first, entry.second, false)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}

class AthenaQuestsCommand : BrigadierCommand("brquests", "Shows your active BR quests.", arrayOf("challenges", "chals")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(argument("tab", greedyString())
			.executes { execute(it.source, getString(it, "tab").toLowerCase()) }
		)

	private fun execute(source: CommandSourceStack, search: String? = null): Int {
		source.ensureSession()
		var tab: RewardCategoryTabData? = null
		if (search != null) {
			val tabs = getTabs()
			tab = tabs.search(search) { it.DisplayName.format()!! }
				?: throw SimpleCommandExceptionType(LiteralMessage("No matches found for \"$search\". Available options:\n${tabs.joinToString("\n") { "\u2022 " + it.DisplayName.format().orDash() }}")).create()
		}
		source.loading("Getting challenges")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val entries = mutableListOf<FortItemStack>()
		for (item in athena.items.values) {
			if (item.primaryAssetType != "Quest") {
				continue
			}
			val defData = item.defData
			if (defData !is FortQuestItemDefinition || defData.bHidden == true || item.attributes["quest_state"]?.asString != "Active") {
				continue
			}
			if (tab != null) {
				val bundleDef = athena.items[item.attributes["challenge_bundle_id"]?.asString]?.defData as? FortChallengeBundleItemDefinition
					?: continue
				val tags = bundleDef.GameplayTags ?: FGameplayTagContainer()
				if (!tab.IncludeTag.TagName.isNone() && tags.getValue(tab.IncludeTag.toString()) == null
					|| !tab.ExcludeTag.TagName.isNone() && tags.getValue(tab.ExcludeTag.toString()) != null) {
					continue
				}
			}
			entries.add(item)
		}
		if (entries.isNotEmpty()) {
			entries.sortWith { a, b ->
				val rarity1 = a.rarity
				val rarity2 = b.rarity
				val rarityCmp = rarity2.compareTo(rarity1)
				if (rarityCmp != 0) {
					rarityCmp
				} else {
					val tandem1 = (a.defData as? FortQuestItemDefinition)?.TandemCharacterData?.load<FortTandemCharacterData>()?.DisplayName?.format() ?: ""
					val tandem2 = (b.defData as? FortQuestItemDefinition)?.TandemCharacterData?.load<FortTandemCharacterData>()?.DisplayName?.format() ?: ""
					val tandemCmp = tandem1.compareTo(tandem2, true)
					if (tandemCmp != 0) {
						tandemCmp
					} else { // custom, game does not sort by challenge bundle
						val challengeBundleId1 = a.attributes["challenge_bundle_id"]?.asString ?: ""
						val challengeBundleId2 = b.attributes["challenge_bundle_id"]?.asString ?: ""
						challengeBundleId1.compareTo(challengeBundleId2, true)
					}
				}
			}
			source.replyPaginated(entries, 15) { content, page, pageCount ->
				val entriesStart = page * 15 + 1
				val entriesEnd = entriesStart + content.size
				val value = content.joinToString("\n") {
					renderChallenge(it, "• ", "\u00a0\u00a0\u00a0", showRarity = true)
				}
				val embed = source.createEmbed()
					.setTitle("Battle Royale Quests" + if (tab != null) " / " + tab.DisplayName.format() else "")
					.setDescription("Showing %,d to %,d of %,d entries\n\n%s".format(entriesStart, entriesEnd - 1, entries.size, value))
					.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				MessageBuilder(embed)
			}
		} else {
			if (tab != null) {
				throw SimpleCommandExceptionType(LiteralMessage("You have no quests in category ${tab.DisplayName.format()}.")).create()
			} else {
				throw SimpleCommandExceptionType(LiteralMessage("You have no quests.")).create()
			}
		}
		return Command.SINGLE_SUCCESS
	}

	private fun getTabs(): List<RewardCategoryTabData> {
		val d = loadObject<UObject>("/Game/Athena/HUD/Minimap/AthenaMapGamePanel_BP.TabList_QuestCategories") /*AthenaMapGamePanel_BP_C:WidgetTree.TabList_QuestCategories*/
			?: throw SimpleCommandExceptionType(LiteralMessage("Object defining categories not found.")).create()
		return d.getProp<List<RewardCategoryTabData>>("RewardTabsData", TypeToken.getParameterized(List::class.java, RewardCategoryTabData::class.java).type)!!
	}
}

class QuestCommand : BrigadierCommand("quest", "Shows the details of a quest by description.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("item", item(true))
			.executes {
				val source = it.source
				source.ensureSession()
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
				val campaign = source.api.profileManager.getProfileData("campaign")
				questDetails(source, getItem(it, "item", campaign, "Quest"))
			}
		)

	private fun questDetails(source: CommandSourceStack, item: FortItemStack): Int {
		val conditionalCondition = false
		val quest = item.defData as? FortQuestItemDefinition
			?: throw SimpleCommandExceptionType(LiteralMessage("Not a quest item. It is ${item.defData?.clazz?.name}.")).create()
		val embed = EmbedBuilder()
			.setColor(COLOR_INFO)
			.setAuthor(quest.DisplayName?.format(), null, Utils.benBotExportAsset(quest.LargePreviewImage?.toString()))
			.setDescription(quest.Description?.format())
		val objectives = renderQuestObjectives(item)
		if (objectives.isNotEmpty()) {
			embed.addField("Objectives", objectives, false)
		}
		val rewardLines = renderQuestRewards(item, conditionalCondition)
		if (rewardLines.isNotEmpty()) {
			embed.addField("Rewards", rewardLines, false)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}

fun renderQuestObjectives(item: FortItemStack, short: Boolean = false): String {
	val objectives = (item.defData as FortQuestItemDefinition).Objectives.filter { !it.bHidden }
	return objectives.joinToString("\n") {
		val completion = Utils.getCompletion(it, item)
		val objectiveCompleted = completion >= it.Count
		val sb = StringBuilder(if (objectiveCompleted) "`☑` ~~" else "`☐` ")
		sb.append(if (short) it.HudShortDescription else it.Description)
		if (it.Count > 1) {
			sb.append(" [%,d/%,d]".format(completion, it.Count))
		}
		if (objectiveCompleted) {
			sb.append("~~")
		}
		sb.toString()
	}
}

fun renderQuestRewards(item: FortItemStack, conditionalCondition: Boolean): String {
	val quest = item.defData as FortQuestItemDefinition
	val rewardLines = mutableListOf<String>()
	quest.Rewards?.forEach { reward ->
		if (reward.ItemPrimaryAssetId.PrimaryAssetType.Name.text != "Quest") {
			rewardLines.add("\u2022 " + reward.render(1f, conditionalCondition))
		}
	}
	quest.RewardsTable?.value?.rows
		?.mapValues { it.value.mapToClass(FortQuestRewardTableRow::class.java) }
		?.filter { it.value.QuestTemplateId == "*" || it.value.QuestTemplateId == item.templateId && !it.value.Hidden }
		?.render("", "", 1f, false, conditionalCondition)
		?.let(rewardLines::addAll)
	return rewardLines.joinToString("\n")
}

fun replaceQuest(source: CommandSourceStack, profileId: String, questIndex: Int, questsGetter: (McpProfile) -> List<FortItemStack>): Int {
	source.ensureSession()
	source.loading("Getting quests")
	source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
	var profile = source.api.profileManager.getProfileData(profileId)
	val canReceiveMtxCurrency = profile.items.values.any { it.templateId == "Token:receivemtxcurrency" }
	val currentDailies = questsGetter(profile)
	val questToReplace = if (questIndex != -1) {
		currentDailies.getOrNull(questIndex - 1) ?: throw SimpleCommandExceptionType(LiteralMessage("Invalid daily quest number.")).create()
	} else {
		var firstReducedXpQuest: FortItemStack? = null
		var optimalQuestToReplace: FortItemStack? = null
		for (quest in currentDailies) {
			val isReducedXp = (quest.attributes["xp_reward_scalar"]?.asFloat ?: 1f) < 1f
			if (!isReducedXp) {
				continue
			}
			if (firstReducedXpQuest == null) {
				firstReducedXpQuest = quest
			}
			// I usually replace location based daily quests
			val isLocationQuest = quest.defData?.GameplayTags?.any { it.toString().startsWith("Athena.Location") } == true
			if (isLocationQuest) {
				optimalQuestToReplace = quest
				break
			}
		}
		optimalQuestToReplace ?: firstReducedXpQuest ?: throw SimpleCommandExceptionType(LiteralMessage("Can't find a quest that's good to replace.")).create()
	}
	val remainingRerolls = (profile.stats as IQuestManager).questManager?.dailyQuestRerolls ?: 0
	if (remainingRerolls <= 0) {
		throw SimpleCommandExceptionType(LiteralMessage("You ran out of daily quest rerolls for today.")).create()
	}
	val embed = source.createEmbed()
	var confirmationMessage: Message? = null
	if (questIndex != -1) {
		confirmationMessage = source.complete(null, embed.setColor(BrigadierCommand.COLOR_WARNING)
			.setTitle("Replace?")
			.setDescription(renderChallenge(questToReplace, conditionalCondition = canReceiveMtxCurrency))
			.build(), confirmationButtons())
		if (!confirmationMessage.awaitConfirmation(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
	}
	source.api.profileManager.dispatchClientCommandRequest(FortRerollDailyQuest().apply { questId = questToReplace.itemId }, profileId).await()
	profile = source.api.profileManager.getProfileData(profileId)
	embed.setColor(BrigadierCommand.COLOR_SUCCESS)
		.setTitle("✅ Replaced")
		.addField("Here are your daily quests now:", questsGetter(profile)
			.mapIndexed { i, it -> renderChallenge(it, "${i + 1}. ", "\u00a0\u00a0\u00a0", conditionalCondition = canReceiveMtxCurrency) }
			.joinToString("\n")
			.takeIf { it.isNotEmpty() } ?: "You have no active daily quests", false)
	confirmationMessage?.editMessageEmbeds(embed.build())?.complete() ?: source.complete(null, embed.build())
	return Command.SINGLE_SUCCESS
}

fun renderChallenge(item: FortItemStack, prefix: String = "", rewardsPrefix: String? = "", isAthenaDaily: Boolean = false, showRarity: Boolean = isAthenaDaily, conditionalCondition: Boolean = false, allowBold: Boolean = true): String {
	val (completion, max) = getQuestCompletion(item)
	val xpRewardScalar = item.attributes["xp_reward_scalar"]?.asFloat ?: 1f
	var dn = item.displayName
	if (dn.isEmpty()) {
		dn = item.templateId
	}
	val rarity = if (showRarity) {
		var rarity = item.rarity
		item.attributes["quest_rarity"]?.asString?.let { overrideRarity ->
			EFortRarity.values().firstOrNull { it.name.equals(overrideRarity, true) }?.let {
				rarity = it
			}
		}
		"[${rarity.rarityName.format()}] "
	} else ""
	val boldTitle = if (allowBold) "**" else ""
	val sb = StringBuilder("%s%s%s%s%s ".format(prefix, rarity, boldTitle, dn, boldTitle))
	if (rewardsPrefix != null) {
		sb.append("[%,d/%,d]".format(completion, max))
	} else {
		sb.append("**[%,d/%,d]**".format(completion, max))
	}
	val quest = item.defData as FortQuestItemDefinition
	val bold = allowBold && isAthenaDaily && xpRewardScalar == 1f
	quest.Rewards?.forEach { reward ->
		if (reward.ItemPrimaryAssetId.PrimaryAssetType.Name.text != "Quest") {
			sb.append('\n')
			if (bold) sb.append("**")
			sb.append(rewardsPrefix).append(reward.render(xpRewardScalar, conditionalCondition))
			if (bold) sb.append("**")
		}
	}
	if (rewardsPrefix != null) {
		quest.RewardsTable?.value?.rows
			?.mapValues { it.value.mapToClass(FortQuestRewardTableRow::class.java) }
			?.filter { it.value.QuestTemplateId == "*" || it.value.QuestTemplateId == item.templateId && !it.value.Hidden }
			?.render(rewardsPrefix, rewardsPrefix, xpRewardScalar, bold, conditionalCondition)
			?.forEach { sb.append('\n').append(it) }
	}
	return sb.toString()
}

fun getQuestCompletion(item: FortItemStack, allowCompletionCountOverride: Boolean = true): Pair<Int, Int> {
	val quest = item.defData as? FortQuestItemDefinition ?: return 0 to 0
	var completion = 0
	var max = 0
	for (objective in quest.Objectives) {
		if (objective.bHidden) {
			continue
		}
		completion += Utils.getCompletion(objective, item)
		max += objective.Count
	}
	if (allowCompletionCountOverride && quest.ObjectiveCompletionCount != null) {
		max = quest.ObjectiveCompletionCount
	}
	return completion to max
}