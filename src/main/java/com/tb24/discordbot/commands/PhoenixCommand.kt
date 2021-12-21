package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.scheduledevents.CalendarDownload.EventRecord
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.fort.objects.rows.FortPhoenixLevelRewardData
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import kotlin.jvm.internal.Ref.ObjectRef

class PhoenixCommand : BrigadierCommand("ventures", "Shows the given user's venture level, xp, and how much is needed to level up.", arrayOf("vt")) {
	private val phoenixLevelRewardsTable by lazy { loadObject<UDataTable>("/SaveTheWorld/Balance/DataTables/PhoenixLevelRewardsTable.PhoenixLevelRewardsTable")?.rows?.values?.map { it.mapToClass<FortPhoenixLevelRewardData>() } }
	private val noDataErr = SimpleCommandExceptionType(LiteralMessage("No data"))

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::display, "Getting Ventures data")
		.then(literal("rewards")
			.executes { c ->
				val currentEvent = ObjectRef<EventRecord>()
				val levels = getLevelRewards(c.source, currentEvent)
				c.source.message.replyPaginated(levels, 10) { content, page, pageCount ->
					MessageBuilder(EmbedBuilder()
						.setAuthor("Ventures: ${currentEvent.element.eventType.substringAfterLast('.')}")
						.setTitle("Rewards")
						.apply {
							content.forEachIndexed { i, entry ->
								val listIndex = page * 10 + i
								addField("Level ${Formatters.num.format(listIndex + 1)}${(if (listIndex < levels.size - 1) " (${Formatters.num.format(levels[listIndex + 1].TotalRequiredXP - entry.TotalRequiredXP)} XP)" else "")}", entry.VisibleReward.joinToString("\n") { FortItemStack(it.TemplateId, it.Quantity).renderWithIcon() }.takeIf { it.isNotEmpty() } ?: "No rewards", true)
							}
						}
						.setFooter("Page %,d of %,d".format(page + 1, pageCount))
					).build()
				}
				Command.SINGLE_SUCCESS
			}
		)

	private fun display(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		val xpItem = campaign.items.values.firstOrNull { it.templateId == "AccountResource:phoenixxp" } ?: FortItemStack("AccountResource:phoenixxp", 0)
		val xpQuantity = xpItem.quantity
		val currentEvent = ObjectRef<EventRecord>()
		val levels = getLevelRewards(source, currentEvent)
		var levelData: FortPhoenixLevelRewardData? = null
		var levelIdx = levels.size
		while (levelIdx-- > 0) {
			if (xpQuantity >= levels[levelIdx].TotalRequiredXP) {
				levelData = levels[levelIdx]
				break
			}
		}
		if (levelData == null) {
			throw SimpleCommandExceptionType(LiteralMessage("Level data not found.")).create()
		}
		val nextLevelData = levels.getOrNull(levelIdx + 1)
		var nextMajorIdx = findMajor(levelIdx + /*next level*/1, levels)
		if (nextMajorIdx == levelIdx + 1) { // next major reward is the next level, find again starting from next level + 1
			nextMajorIdx = findMajor(nextMajorIdx + 1, levels)
		}
		val nextMajorData = levels.getOrNull(nextMajorIdx)
		val embed = source.createEmbed(campaign.owner, true)
			.setTitle("Ventures: ${currentEvent.element.eventType.substringAfterLast('.')}")
			.setDescription("**Level %,d** - %s%,d\n%s".format(levelIdx + 1, getItemIconEmoji(xpItem)?.run { "$asMention " } ?: "", xpQuantity, if (nextLevelData != null) {
				val current = xpQuantity - levelData.TotalRequiredXP
				val delta = nextLevelData.TotalRequiredXP - levelData.TotalRequiredXP
				val lastLevel = levels.last()
				"`%s`\n%,d / %,d\n%,d XP to level %,d.".format(
					Utils.progress(current, delta, 32),
					current, delta,
					//nextLevelData.TotalRequiredXP - xpQuantity,
					lastLevel.TotalRequiredXP - xpQuantity,
					levels.size
				)
			} else "Max level."))
		if (nextLevelData != null) {
			embed.addField("Rewards for level ${Formatters.num.format(levelIdx + /*next level*/1 + /*index offset*/1)}", nextLevelData.VisibleReward.joinToString("\n") { FortItemStack(it.TemplateId, it.Quantity).renderWithIcon() }, true)
		}
		if (nextMajorData != null && nextMajorData != nextLevelData) {
			embed.addField("Rewards for level ${Formatters.num.format(nextMajorIdx + /*index offset*/1)}", nextMajorData.VisibleReward.joinToString("\n") { FortItemStack(it.TemplateId, it.Quantity).renderWithIcon() }, true)
		}
		val seasonEndsText = "%s season ends %s".format(currentEvent.element.eventType.substringAfterLast('.'), currentEvent.element.activeUntil.relativeFromNow())
		val venturesQuests = campaign.items.values.filter { it.primaryAssetType == "Quest" && it.attributes["quest_state"]?.asString == "Active" && (it.defData as? FortQuestItemDefinition)?.Category?.rowName?.text?.startsWith("Phoenix_") == true }
			.sortedByDescending { (it.defData as FortQuestItemDefinition).SortPriority ?: 0 }
		for (item in venturesQuests) {
			val defData = item.defData as FortQuestItemDefinition
			var title = (textureEmote(defData.LargePreviewImage?.toString())?.run { "$asMention " } ?: "") + defData.DisplayName.format()
			item.primaryAssetName.substringAfterLast('_').toIntOrNull()?.let {
				title += " (${Formatters.num.format(it)}/12)"
			}
			val objectives = renderQuestObjectives(item, true)
			val rewards = renderQuestRewards(item, false)
			embed.addField(title, objectives + '\n' + rewards, false)
		}
		embed.setFooter(seasonEndsText)
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun getLevelRewards(source: CommandSourceStack, activeEvent: ObjectRef<EventRecord>): MutableList<FortPhoenixLevelRewardData> {
		val table = phoenixLevelRewardsTable ?: throw noDataErr.create()
		val calendarResponse = source.api.fortniteService.calendarTimeline().exec().body()!!
		val clientEventsState = calendarResponse.channels["client-events"]!!.currentState
		val cachedActiveEvents = hashMapOf<String, Boolean>()
		val levels = mutableListOf<FortPhoenixLevelRewardData>()
		for (row in table) {
			val eventTag = row.EventTag
			if (cachedActiveEvents.getOrPut(eventTag) {
					val event = clientEventsState.getEvent(eventTag)
					if (event?.isActive == true) {
						activeEvent.element = event
						true
					} else false
				}) {
				levels.add(row)
			}
		}
		return levels
	}

	private fun findMajor(startIdx: Int, levels: List<FortPhoenixLevelRewardData>): Int {
		for (nextMajorIdx in startIdx until levels.size) {
			val entry = levels[nextMajorIdx]
			if (entry.bIsMajorReward) {
				return nextMajorIdx
			}
		}
		return -1
	}
}