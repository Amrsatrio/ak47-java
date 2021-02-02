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
import com.tb24.discordbot.commands.arguments.ItemArgument.Companion.getItem
import com.tb24.discordbot.commands.arguments.ItemArgument.Companion.item
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.RewardCategoryTabData
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.attributes.IQuestManager
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.subgame.FortRerollDailyQuest
import com.tb24.fn.util.format
import com.tb24.uasset.AssetManager
import com.tb24.uasset.getProp
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.fort.exports.AthenaDailyQuestDefinition
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition.EFortQuestType
import me.fungames.jfortniteparse.fort.objects.rows.FortQuestRewardTableRow
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTagContainer
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.font.TextAttribute
import java.awt.geom.GeneralPath
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileReader
import java.text.AttributedString
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.system.exitProcess

class AthenaDailyChallengesCommand : BrigadierCommand("dailychallenges", "Manages your active BR daily challenges.", arrayOf("dailychals", "brdailies")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting challenges")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val numRerolls = (athena.stats.attributes as IQuestManager).questManager?.dailyQuestRerolls ?: 0
			var description = getAthenaDailyQuests(athena)
				.joinToString("\n") { renderChallenge(it, "• ", "\u00a0\u00a0\u00a0", true) }
			if (description.isEmpty()) {
				description = "You have no quick challenges"
			} else if (numRerolls > 0) {
				description += "\n\n" + "You have %,d reroll(s) remaining today.\nUse `%s%s replace <%s>` to replace one."
					.format(numRerolls, source.prefix, c.commandName, "quick challenge #")
			}
			source.complete(null, source.createEmbed()
				.setTitle("Quick Challenges")
				.setDescription(description)
				.build())
			Command.SINGLE_SUCCESS
		}
		.then(literal("replace")
			.then(argument("quick challenge #", integer())
				.executes { replaceQuest(it.source, "athena", getInteger(it, "quick challenge #"), ::getAthenaDailyQuests) }
			)
		)

	private fun getAthenaDailyQuests(athena: McpProfile) =
		athena.items.values
			.filter { it.primaryAssetType == "Quest" && it.attributes["quest_state"]?.asString == "Active" && it.defData is AthenaDailyQuestDefinition }
			.sortedBy { it.displayName }
}

class DailyQuestsCommand : BrigadierCommand("dailyquests", "Manages your active STW daily quests.", arrayOf("dailies", "stwdailies")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::displayDailyQuests, "Getting quests")
		.then(literal("replace")
			.then(argument("daily quest #", integer())
				.executes { replaceQuest(it.source, "campaign", getInteger(it, "daily quest #"), ::getCampaignDailyQuests) }
			)
		)

	private fun displayDailyQuests(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		val canReceiveMtxCurrency = campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
		val numRerolls = (campaign.stats.attributes as IQuestManager).questManager?.dailyQuestRerolls ?: 0
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
			tab = tabs.firstOrNull {
				val tabName = it.DisplayName.format()!!.toLowerCase()
				tabName == search || tabName.startsWith(search) || Utils.damerauLevenshteinDistance(search, tabName) < tabName.length / 2 + 5
			} ?: throw SimpleCommandExceptionType(LiteralMessage("No matches found for \"$search\". Available options:\n${tabs.joinToString("\n") { "\u2022 " + it.DisplayName.format().orDash() }}")).create()
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
			entries.sortByDescending { it.rarity }
			source.message.replyPaginated(entries, 15, source.loadingMsg) { content, page, pageCount ->
				val entriesStart = page * 15 + 1
				val entriesEnd = entriesStart + content.size
				val value = content.joinToString("\n") {
					renderChallenge(it, "• ", "\u00a0\u00a0\u00a0")
				}
				val embed = source.createEmbed()
					.setTitle("Battle Royale Quests" + if (tab != null) " / " + tab.DisplayName.format() else "")
					.setDescription("Showing %,d to %,d of %,d entries\n\n%s".format(entriesStart, entriesEnd - 1, entries.size, value))
					.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				MessageBuilder(embed).build()
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
		val d = AssetManager.INSTANCE.provider.loadGameFile("/Game/Athena/HUD/Minimap/AthenaMapGamePanel_BP")?.exportsLazy?.get(7)?.value
			?: throw SimpleCommandExceptionType(LiteralMessage("Object defining categories not found.")).create()
		return d.getProp<List<RewardCategoryTabData>>("RewardTabsData", TypeToken.getParameterized(List::class.java, RewardCategoryTabData::class.java).type)!!
	}
}

class QuestCommand : BrigadierCommand("quest", "Shows the details of a quest by description.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("item", item())
			.executes {
				val source = it.source
				source.ensureSession()
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
				val campaign = source.api.profileManager.getProfileData("campaign")
				questDetails(source, getItem(it, "item", campaign))
			}
		)

	private fun questDetails(source: CommandSourceStack, item: FortItemStack): Int {
		val conditionalCondition = false
		val quest = item.defData as? FortQuestItemDefinition
			?: throw SimpleCommandExceptionType(LiteralMessage("Not a quest item. It is ${item.defData?.clazz?.name}.")).create()
		val embed = EmbedBuilder()
			.setColor(COLOR_SUCCESS)
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
	source.api.profileManager.dispatchClientCommandRequest(FortRerollDailyQuest().apply { questId = questToReplace.itemId }, profileId).await()
	profile = source.api.profileManager.getProfileData(profileId)
	source.complete(null, source.createEmbed()
		.setTitle("✅ Replaced a daily quest")
		.setDescription("Here's your daily quests now:")
		.addField("Daily Quests", questsGetter(profile)
			.mapIndexed { i, it -> renderChallenge(it, "${i + 1}. ", "\u00a0\u00a0\u00a0", conditionalCondition = canReceiveMtxCurrency) }
			.joinToString("\n")
			.takeIf { it.isNotEmpty() } ?: "You have no active daily quests", false)
		.build())
	return Command.SINGLE_SUCCESS
}

fun renderChallenge(item: FortItemStack, prefix: String = "", rewardsPrefix: String = "", canBold: Boolean = false, conditionalCondition: Boolean = false): String {
	val (completion, max) = getQuestCompletion(item)
	val xpRewardScalar = item.attributes["xp_reward_scalar"]?.asFloat ?: 1f
	var dn = item.displayName
	if (dn.isEmpty()) {
		dn = item.templateId
	}
	val sb = StringBuilder("%s**%s** ( %,d / %,d )".format(prefix, dn, completion, max))
	val quest = item.defData as FortQuestItemDefinition
	val bold = canBold && xpRewardScalar == 1f
	quest.Rewards?.forEach { reward ->
		if (reward.ItemPrimaryAssetId.PrimaryAssetType.Name.text != "Quest") {
			sb.append('\n')
			if (bold) sb.append("**")
			sb.append(rewardsPrefix).append(reward.render(xpRewardScalar, conditionalCondition))
			if (bold) sb.append("**")
		}
	}
	quest.RewardsTable?.value?.rows
		?.mapValues { it.value.mapToClass(FortQuestRewardTableRow::class.java) }
		?.filter { it.value.QuestTemplateId == "*" || it.value.QuestTemplateId == item.templateId && !it.value.Hidden }
		?.render(rewardsPrefix, rewardsPrefix, xpRewardScalar, bold, conditionalCondition)
		?.forEach(sb.append('\n')::append)
	return sb.toString()
}

private fun getQuestCompletion(item: FortItemStack): Pair<Int, Int> {
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
	return Pair(completion, max)
}

fun main() {
//	AssetManager.INSTANCE.loadPaks()
	val profile = FileReader("D:\\Downloads\\ComposeMCP-amrsatrio-queryprofile-athena-18824.json").use {
		EpicApi.GSON.fromJson(it, McpProfile::class.java)
	}
	val questsToDisplay = mutableListOf<QuestBubbleContainer>()
	fun h(Rarity: EFortRarity): QuestBubbleContainer {
//		val defData = item.defData
		return QuestBubbleContainer(arrayOf(0, 0x61BF00, 0, 0xCE59FF, 0xFF8B19, 0, 0, 0)[Rarity.ordinal])
	}
	questsToDisplay += h(EFortRarity.Epic)
	val scale = 2f
	val pngData = createAndDrawCanvas((512 * scale).toInt(), (512 * scale).toInt()) { ctx ->
		val baseFont = Font.createFont(Font.TRUETYPE_FONT, File("C:\\Users\\satri\\AppData\\Local\\Microsoft\\Windows\\Fonts\\zh-cn.ttf"))
		ctx.font = /*resources.burbankSmallBold*/baseFont.deriveFont(Font.PLAIN, 20f * scale)
		ctx.drawString(profile.version, 0, 0 + ctx.fontMetrics.ascent)
		var cur = 0
		questsToDisplay.forEach {
			it.measure(ctx, scale)
//			it.w = (512 * scale).toInt()
			it.draw(ctx, scale)
			cur += it.h
		}
	}.toPngArray()
	File("test_quests_s15.png").writeBytes(pngData)
	exitProcess(0)
}

class QuestEntryContainer(
	val bubble: QuestBubbleContainer
) {
	fun draw(ctx: Graphics2D, scale: Float) {
		val charAvatarSize = 48 * scale
	}
}

class QuestBubbleContainer(
	val rarityColor: Int,
	val displayName: String = "Display Name",
	val shortDescription: String = "Short Description",
	val description: String = "Shotgun eliminations",
	val stageIdx: Int = 0,
	val stageNum: Int = 4,
	val completed: Boolean = false,
	val completion: Int = 2,
	val max: Int = 3,
) {
	var avatarSize = 0f
	var avatarAreaW = 0f
	var barWidth = 0f
	var triangleWidth = 0f
	var totalLeftWidth = 0f
	lateinit var descriptionText: String
	var contentWidth = 0
	var contentHeight = 0
	var w = 0
	var h = 0

	fun measure(ctx: Graphics2D, scale: Float) {
		avatarSize = 48 * scale
		avatarAreaW = avatarSize + 8 * scale
		barWidth = 10 * scale
		triangleWidth = 16 * scale
		totalLeftWidth = triangleWidth + barWidth
		contentWidth = 0
		contentHeight = 0
		ctx.font = ctx.font.deriveFont(14 * scale)
		descriptionText = description
		if (stageNum > 1) {
			descriptionText = "Stage %,d of %,d - %s".format(stageIdx + 1, stageNum, description)
		}
		contentWidth = max(contentWidth, ctx.fontMetrics.stringWidth(descriptionText))
		contentHeight += (ctx.fontMetrics.height + 6 * scale).toInt()
		contentHeight += ctx.fontMetrics.height
		w = (avatarAreaW.toInt() + totalLeftWidth + contentWidth + 24 * scale).toInt()
		h = (contentHeight + 16 * scale).toInt()
	}

	fun draw(ctx: Graphics2D, scale: Float) {
		ctx.color = 0xFFFF00FF.awtColor()

		ctx.fillRect(0, 0, avatarSize.toInt(), avatarSize.toInt())
		ctx.drawImage(drawBubble((totalLeftWidth + contentWidth + 24 * scale).toInt(), (contentHeight + 16 * scale).toInt(), scale), avatarAreaW.toInt(), 0, null)
		val contentLeft = avatarAreaW + totalLeftWidth + 12 * scale
		var yCur = 8 * scale
		ctx.font = ctx.font.deriveFont(14 * scale)
		ctx.color = 0x7FD3FF.awtColor()

		// description
		ctx.drawString(descriptionText, contentLeft, yCur + ctx.fontMetrics.ascent)
		yCur += ctx.fontMetrics.height + 6 * scale

		// time remaining
		val l = 691200000L
		val timeRemainingColor = getTimeRemainingColor(l)
		val timerIcon = ImageIO.read(File("C:\\Users\\satri\\Desktop\\ui_timer_64x.png"))
		val tW = timerIcon.width
		val tH = timerIcon.height
		val pixels = timerIcon.getRGB(0, 0, tW, tH, null, 0, tW)
		val handPixels = IntArray(pixels.size)
		for ((i, it) in pixels.withIndex()) {
			var outAlpha = (it shr 16) and 0xFF // red channel: base
			outAlpha -= (it shr 8) and 0xFF // green channel: inner
			outAlpha = max(outAlpha, 0)
			pixels[i] = (outAlpha shl 24) or timeRemainingColor
			handPixels[i] = ((it and 0xFF) shl 24) or timeRemainingColor // blue channel: hand
		}
		val frame = BufferedImage(tW, tH, BufferedImage.TYPE_INT_ARGB)
		frame.setRGB(0, 0, tW, tH, pixels, 0, tW)
		val hand = BufferedImage(tW, tH, BufferedImage.TYPE_INT_ARGB)
		hand.setRGB(0, 0, tW, tH, handPixels, 0, tW)
		val iconY = (yCur - 6 * scale).toInt()
		val iconSize = (28 * scale).toInt()
		ctx.drawImage(frame, contentLeft.toInt(), iconY, iconSize, iconSize, null)
		val saveT = ctx.transform
		val oX = 0.49 * iconSize
		val oY = 0.575 * iconSize
		val currentSecondsInHour = (System.currentTimeMillis() / 1000) % (60 * 60)
		ctx.rotate(Math.toRadians(currentSecondsInHour.toDouble() / (60 * 60) * 360), contentLeft + oX, iconY + oY)
		ctx.drawImage(hand, contentLeft.toInt(), iconY, iconSize, iconSize, null)
		ctx.transform = saveT
		ctx.color = timeRemainingColor.awtColor()
		ctx.drawString(StringUtil.formatElapsedTime(l, false).toString().toUpperCase(), contentLeft + iconSize, yCur + ctx.fontMetrics.ascent)

		// completion
		val completionTextRaw = "%,d / %,d".format(completion, max)
		val completionText = AttributedString(completionTextRaw)
		completionText.addAttribute(TextAttribute.FONT, ctx.font)
		completionText.addAttribute(TextAttribute.FOREGROUND, rarityColor.awtColor(), completionTextRaw.indexOf('/'), completionTextRaw.length)
		ctx.color = Color.WHITE
		ctx.drawString(completionText.iterator, w - 12 * scale - ctx.fontMetrics.stringWidth(completionTextRaw), yCur + ctx.fontMetrics.ascent)
	}

	fun drawBubble(w: Int, h: Int, scale: Float) = createAndDrawCanvas(w, h) { ctx ->
		val radius = 8 * scale
		ctx.fill(RoundRectangle2D.Float(triangleWidth, 0f, w - triangleWidth, h.toFloat(), radius * 2, radius * 2))
		ctx.fill(GeneralPath().apply { moveTo(0f, barWidth); lineTo(triangleWidth, barWidth); lineTo(triangleWidth, barWidth + triangleWidth); closePath() })
		ctx.composite = AlphaComposite.SrcIn
		ctx.color = rarityColor.awtColor()
		ctx.fillRect(0, 0, totalLeftWidth.toInt(), h)
		ctx.color = 0x000C59.withAlpha(.8f).awtColor()
		ctx.fillRect(totalLeftWidth.toInt(), 0, w - totalLeftWidth.toInt(), h)
	}
}

inline fun Number.withAlpha(v: Float) = (v * 255 + 0.5).toInt() shl 24 or toInt()

fun getTimeRemainingColor(l: Long) = when {
	l <= 1 * 24 * 60 * 60 * 1000 /*1d*/ -> 0x00A6FF
	else -> 0x00A6FF
}