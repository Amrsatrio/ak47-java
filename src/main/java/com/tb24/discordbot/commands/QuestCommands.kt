package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.Rune
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.subgame.FortRerollDailyQuest
import com.tb24.fn.model.mcpprofile.stats.IQuestManager
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.fn.util.getPathName
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.AthenaDailyQuestDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.fort.objects.rows.FortCategoryTableRow
import me.fungames.jfortniteparse.fort.objects.rows.FortQuestRewardTableRow
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.ComponentInteraction
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import net.dv8tion.jda.api.interactions.components.selections.SelectMenu
import net.dv8tion.jda.api.interactions.components.selections.SelectMenuInteraction
import java.util.concurrent.CompletableFuture

class AthenaDailyChallengesCommand : BrigadierCommand("dailychallenges", "Manages your active BR daily challenges.", arrayOf("dailychals", "brdailies", "bd")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting challenges")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val numRerolls = /*(athena.stats as IQuestManager).questManager?.dailyQuestRerolls ?:*/ 0
			var description = getAthenaDailyQuests(athena)
				.mapIndexed { i, it -> renderChallenge(it, "${i + 1}. ", "\u2800", isAthenaDaily = true) }
				.joinToString("\n")
			if (description.isEmpty()) {
				description = "You have no daily challenges"
			} else if (numRerolls > 0 && Rune.isBotDev(source)) {
				description += "\n\n" + "Use `%s%s replace <%s>` to replace one."
					.format(source.prefix, c.commandName, "daily challenge #")
			}
			source.complete(null, source.createEmbed()
				.setTitle("Daily Challenges")
				.setDescription(description)
				.build())
			Command.SINGLE_SUCCESS
		}
		/*.then(literal("replace")
			.executes { replaceQuest(it.source, "athena", -1, ::getAthenaDailyQuests) }
			.then(argument("daily challenge #", integer())
				.executes { replaceQuest(it.source, "athena", getInteger(it, "daily challenge #"), ::getAthenaDailyQuests) }
			)
		)*/

	private fun getAthenaDailyQuests(athena: McpProfile) =
		athena.items.values
			.filter { it.primaryAssetType == "Quest" && it.attributes["quest_state"]?.asString == "Active" && it.defData is AthenaDailyQuestDefinition }
			.sortedBy { it.displayName }
}

val questCategoryTable by lazy { loadObject<UDataTable>("/Game/Quests/QuestCategoryTable.QuestCategoryTable")!! }

abstract class BaseQuestsCommand(name: String, description: String, private val categoryName: String, private val replaceable: Boolean, aliases: Array<String> = emptyArray()) : BrigadierCommand(name, description, aliases) {
	companion object {
		private val dailyQuestsByZone = mapOf(
			"sub" to "tv,teddy,gnome,rural,suburb,treasures,safe,seesaws,fire,server",
			"city" to "cities,fire,treasures,safe,server,arcade,gnome,teddy,tv",
			"lake" to "fire,arcade,server,safe",
			"indu" to "industrial,fire,prop,transformer,server"
		)
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> {
		val node = newRootNode().withPublicProfile({ source, campaign -> executeQuests(source, campaign, categoryName, replaceable) }, "Getting quests")
		if (replaceable) {
			node.then(literal("replace")
				.then(argument("quest #", integer())
					.executes { replaceQuest(it.source, "campaign", getInteger(it, "quest #")) { getQuestsOfCategory(it, categoryName) } }
				)
			)
		}
		node.then(literal("bulk")
			.executes { executeQuestsBulk(it.source, categoryName) }
			.then(argument("users", UserArgument.users(100))
				.executes { executeQuestsBulk(it.source, categoryName, lazy { UserArgument.getUsers(it, "users").values }) }
			)
		)
		node.then(literal("bulkf")
			.then(argument("filter", greedyString())
				.executes { executeQuestsBulk(it.source, categoryName, filters = getString(it, "filter").split(","))}
			)
		)
		node.then(literal("bulk3")
			.executes { executeQuestsBulk(it.source, categoryName, maxDailiesOnly = true) }
		)
		node.then(literal("bulkz")
			.then(argument("type", greedyString())
				.executes {
					val type = getString(it, "type").toLowerCase()
					if (type !in dailyQuestsByZone) {
						throw SimpleCommandExceptionType(LiteralMessage("Unknown zone type $type. Valid values are: (case insensitive)```\n${dailyQuestsByZone.keys.joinToString()}\n```")).create()
					}
					executeQuestsBulk(it.source, categoryName, filters = dailyQuestsByZone[type]!!.split(','))
				}
			)
		)
		return node
	}

	override fun getSlashCommand(): BaseCommandBuilder<CommandSourceStack> {
		val node = newCommandBuilder().then(subcommand("view", description)
			.withPublicProfile({ source, campaign -> executeQuests(source, campaign, categoryName, replaceable) }, "Getting quests")
		)
		if (replaceable) {
			node.then(subcommand("replace", "Replace a quest displayed in /%s view.".format(name))
				.option(OptionType.INTEGER, "quest-number", "Number of the quest to replace", true)
				.executes { source ->
					replaceQuest(source, "campaign", source.getOption("quest-number")!!.asInt) { getQuestsOfCategory(it, categoryName) }
				}
			)
		}
		node.then(subcommand("bulk", "Multiple users version of /%s view.".format(name))
			.option(OptionType.STRING, "users", "Users to display or leave blank to display your saved accounts", argument = UserArgument.users(100))
			.executes { source ->
				val usersResult = source.getArgument<UserArgument.Result>("users")
				executeQuestsBulk(source, categoryName, usersResult?.let { lazy { it.getUsers(source).values } })
			}
		)
		return node
	}
}

private fun executeQuests(source: CommandSourceStack, campaign: McpProfile, categoryName: String, replaceable: Boolean): Int {
	source.ensureCompletedCampaignTutorial(campaign)
	val category = questCategoryTable.findRowMapped<FortCategoryTableRow>(FName(categoryName))!!
	val canReceiveMtxCurrency = campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
	val numRerolls = (campaign.stats as IQuestManager).questManager.dailyQuestRerolls
	val quests = getQuestsOfCategory(campaign, categoryName)
	val canReplace = replaceable && campaign.owner == source.api.currentLoggedIn && numRerolls > 0
	val buttons = if (canReplace) quests.mapIndexed { i, it ->
		Button.of(ButtonStyle.SECONDARY, i.toString(), "Replace %s".format(it.displayName))
	} else emptyList()
	var description = quests
		.mapIndexed { i, it -> renderChallenge(it, "${i + 1}. ", "\u2800", conditionalCondition = canReceiveMtxCurrency) }
		.joinToString("\n")
	if (description.isEmpty()) {
		description = "You have no active %s".format(category.Name.format())
	}
	val message = source.complete(null, source.createEmbed(campaign.owner)
		.setTitle(category.Name.format())
		.setDescription(description)
		.build(), *if (buttons.isNotEmpty()) arrayOf(ActionRow.of(buttons)) else emptyArray())
	if (buttons.isEmpty()) {
		return Command.SINGLE_SUCCESS
	}
	source.unattended = true
	return replaceQuest(source, "campaign", message.awaitOneComponent(source).componentId.toInt() + 1) { getQuestsOfCategory(campaign, categoryName) }
}

private val xrayIcon by lazy { textureEmote("/Game/UI/Foundation/Textures/Icons/Items/T-Items-Currency-X-RayLlama-L.T-Items-Currency-X-RayLlama-L") }

private fun executeQuestsBulk(source: CommandSourceStack, categoryName: String, usersLazy: Lazy<Collection<GameProfile>>? = null, maxDailiesOnly: Boolean = false, filters: List<String> = listOf()): Int {
	source.conditionalUseInternalSession()
	val foundersWithMaxdailies = mutableListOf<String>()
	val entries = stwBulk(source, usersLazy) { campaign ->
		val completedTutorial = (campaign.items.values.firstOrNull { it.templateId == "Quest:outpostquest_t1_l3" }?.attributes?.get("completion_complete_outpost_1_3")?.asInt ?: 0) > 0
		if (!completedTutorial) return@stwBulk null
		val quests = getQuestsOfCategory(campaign, categoryName)
		if (maxDailiesOnly && quests.size < 3 && categoryName == "DailyQuests") return@stwBulk null
		val rendered = if (filters.isEmpty()) {
			quests.joinToString("\n") { renderChallenge(it, "\u2800", null, allowBold = false) }
		} else {
			quests.filter { quest -> filters.any { quest.displayName.contains(it, true) } }.also { if (it.isEmpty()) return@stwBulk null }.joinToString("\n") { renderChallenge(it, "\u2800", null, allowBold = false) }
		}
		var title = campaign.owner.displayName
		if (categoryName == "DailyQuests") {
			val canReceiveMtxCurrency = campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
			if (canReceiveMtxCurrency) {
				if (quests.size >= 3) {
					foundersWithMaxdailies.add(campaign.owner.displayName)
				}
			} else {
				title = xrayIcon?.formatted + ' ' + title
			}
		}
		title to rendered
	}
	if (entries.isEmpty()) {
		if (maxDailiesOnly) {
			throw SimpleCommandExceptionType(LiteralMessage("None of your accounts have 3 dailies.")).create()
		}
		if (filters.isNotEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("None of your accounts have any quests matching the filter.")).create()
		}
	}
	val embed = EmbedBuilder().setColor(BrigadierCommand.COLOR_INFO)
	var count = 0
	for (entry in entries) {
		if (entry.second.isEmpty()) {
			continue
		}
		if (embed.fields.size == 25) {
			source.complete(null, embed.build())
			embed.clearFields()
		}
		embed.addField(entry.first, entry.second, false)
		++count
	}
	if (count == 0) {
		embed.setTitle("🎉 All completed!")
		if (entries.size > 10) {
			embed.setDescription("That must've taken a while 😩")
		}
	}
	if (foundersWithMaxdailies.isNotEmpty()) {
		if (maxDailiesOnly) {
			embed.setFooter("%d account%s".format(foundersWithMaxdailies.size, if (foundersWithMaxdailies.size == 1) "" else "s"), Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Boost/T-Icon-FoundersBadge-128.T-Icon-FoundersBadge-128"))
		} else if (filters.isNotEmpty()) {
			embed.setFooter("%,d account%s, 3 dailies (%,d): %s".format(entries.size, if (entries.size == 1) "" else "s", foundersWithMaxdailies.size, foundersWithMaxdailies.joinToString(", ")))
		} else {
			embed.setFooter("3 dailies (%d): %s".format(foundersWithMaxdailies.size, foundersWithMaxdailies.joinToString(", ")), Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Boost/T-Icon-FoundersBadge-128.T-Icon-FoundersBadge-128"))
		}
	}
	source.complete(null, embed.build())
	return Command.SINGLE_SUCCESS
}

class DailyQuestsCommand : BaseQuestsCommand("dailyquests", "Manages your active STW daily quests.", "DailyQuests", true, arrayOf("dailies", "stwdailies", "dq"))
class WeeklyQuestsCommand : BaseQuestsCommand("weeklychallenges", "Shows your active STW weekly challenges.", "WeeklyQuests", false, arrayOf("weeklies", "stwweeklies", "wq"))

fun renderQuestObjectives(item: FortItemStack, short: Boolean = false): String {
	val quest = item.defData as FortQuestItemDefinition
	val objectives = quest.Objectives.filter { !it.bHidden }
	return objectives.joinToString("\n") {
		val completion = Utils.getCompletion(it, item)
		val objectiveCompleted = completion >= it.Count
		val sb = StringBuilder(if (objectiveCompleted) "✅ ~~" else "❌ ")
		val description = (if (short) it.HudShortDescription else it.Description)?.format()
		if (!description.isNullOrEmpty()) {
			val minRating = it.minRating
			sb.append(if (minRating != 0) description.replace("[UIRating]", Formatters.num.format(minRating)) else description)
		} else {
			sb.append("No description")
		}
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
	val remainingRerolls = (profile.stats as IQuestManager).questManager.dailyQuestRerolls
	if (remainingRerolls <= 0) {
		throw SimpleCommandExceptionType(LiteralMessage("You ran out of daily quest rerolls for today.")).create()
	}
	val embed = source.createEmbed()
	var confirmationMessage: Message? = null
	if (!source.unattended && questIndex != -1) {
		confirmationMessage = source.complete(null, embed.setColor(BrigadierCommand.COLOR_WARNING)
			.setTitle("Replace?")
			.setDescription(renderChallenge(questToReplace, conditionalCondition = canReceiveMtxCurrency))
			.build(), confirmationButtons())
		if (!confirmationMessage.awaitConfirmation(source).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
	}
	source.api.profileManager.dispatchClientCommandRequest(FortRerollDailyQuest().apply { questId = questToReplace.itemId }, profileId).await()
	profile = source.api.profileManager.getProfileData(profileId)
	embed.setColor(BrigadierCommand.COLOR_SUCCESS)
		.setTitle("✅ Replaced")
		.addField("Here are your daily quests now:", questsGetter(profile)
			.mapIndexed { i, it -> renderChallenge(it, "${i + 1}. ", "\u2800", conditionalCondition = canReceiveMtxCurrency) }
			.joinToString("\n")
			.takeIf { it.isNotEmpty() } ?: "You have no active daily quests", false)
	confirmationMessage?.editMessageEmbeds(embed.build())?.complete() ?: source.complete(null, embed.build())
	return Command.SINGLE_SUCCESS
}

val rewardsTableCache = hashMapOf<String, Map<FName, FortQuestRewardTableRow>>()

fun renderChallenge(item: FortItemStack, prefix: String = "", rewardsPrefix: String? = "", isAthenaDaily: Boolean = false, conditionalCondition: Boolean = false, allowBold: Boolean = true): String {
	val quest = item.defData as FortQuestItemDefinition
	val (completion, max) = getQuestCompletion(item)
	val xpRewardScalar = item.attributes["xp_reward_scalar"]?.asFloat ?: 1f
	var dn = item.displayName
	if (dn.isEmpty()) {
		dn = item.templateId
	}
	val sb = StringBuilder(prefix)
	if (allowBold) sb.append("**")
	sb.append(dn)
	if (allowBold) sb.append("**")
	sb.append(' ')
	var progress = "%,d/%,d".format(completion, max)
	val difficulty = quest.minRating
	if (difficulty != 0) {
		progress = "%,d+ %s".format(difficulty, progress)
	}
	if (rewardsPrefix != null) {
		sb.append("[$progress]")
	} else {
		sb.append("**[$progress]**")
	}
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
		val rewardsTablePath = quest.RewardsTable?.getPathName()
		if (rewardsTablePath != null) {
			rewardsTableCache
				.getOrPut(rewardsTablePath) { quest.RewardsTable.value.rows.mapValues { it.value.mapToClass(FortQuestRewardTableRow::class.java) } }
				.filter { it.value.QuestTemplateId == "*" || it.value.QuestTemplateId == item.templateId && !it.value.Hidden }
				.render(rewardsPrefix, rewardsPrefix, xpRewardScalar, bold, conditionalCondition)
				.forEach { sb.append('\n').append(it) }
		}
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

fun getQuestsOfCategory(campaign: McpProfile, categoryName: String) =
	campaign.items.values
		.filter { it.primaryAssetType == "Quest" && it.attributes["quest_state"]?.asString == "Active" && (it.defData as? FortQuestItemDefinition)?.category?.rowName?.toString() == categoryName }
		.sortedByDescending { (it.defData as FortQuestItemDefinition).SortPriority ?: 0 }

class CategoryPaginatorComponents<T>(val select: SelectMenu.Builder, val event: CompletableFuture<String?>) : PaginatorCustomComponents<T> {
	private var confirmed = false

	override fun modifyComponents(paginator: Paginator<T>, rows: MutableList<ActionRow>) {
		rows.add(ActionRow.of(select.build()))
	}

	override fun handleComponent(paginator: Paginator<T>, item: ComponentInteraction, user: User?) {
		if (!confirmed && item.componentId == "category") {
			confirmed = true
			paginator.source.loadingMsg = item.message
			event.complete((item as SelectMenuInteraction).values.first())
			paginator.stop()
		}
	}

	override fun onEnd(collected: Map<Any, ComponentInteraction>, reason: CollectorEndReason) {
		event.complete(null)
	}
}