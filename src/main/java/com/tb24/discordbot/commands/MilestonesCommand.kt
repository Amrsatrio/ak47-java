package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.replyPaginated
import com.tb24.discordbot.util.shortenUrl
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.FortChallengeBundleItem
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.getDateISO
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import java.text.SimpleDateFormat
import java.util.*

class MilestonesCommand : BrigadierCommand("milestones", "Shows your milestone quests.", arrayOf("punchcards", "rarequests")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { list(it.source) }
		.then(literal("all")
			.executes { list(it.source, true) }
		)
		.then(literal("fortnitegg")
			.executes { fortniteGG(it.source) }
		)

	private fun list(source: CommandSourceStack, showCompleted: Boolean = false): Int {
		source.ensureSession()
		source.loading("Getting challenges")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val entries = mutableListOf<MilestoneEntry>()
		for (item in athena.items.values) {
			if (item.primaryAssetType == "ChallengeBundle" && "_milestone_" in item.primaryAssetName) {
				val entry = MilestoneEntry(item, athena)
				if (showCompleted || !entry.isCompleted) {
					entries.add(entry)
				}
			}
		}
		// Better way, but slower:
		/*val schedule = athena.items.values.firstOrNull { it.primaryAssetType == "ChallengeBundleSchedule" && (it.defData as FortChallengeBundleScheduleDefinition).CategoryTag.toString() == "ChallengeCategory.PunchCard" }
			?: throw SimpleCommandExceptionType(LiteralMessage("Schedule not found.")).create()
		for (it in schedule.attributes.getAsJsonArray("granted_bundles")) {
			val bundle = athena.items[it.asString]
				?: throw SimpleCommandExceptionType(LiteralMessage("Referenced challenge bundle `${it.asString}` not found.")).create()
			val entry = MilestoneEntry(bundle, athena)
			if (showCompleted || !entry.isCompleted) {
				entries.add(entry)
			}
		}*/
		entries.sort()
		source.replyPaginated(entries, 10) { content, page, pageCount ->
			val entriesStart = page * 10 + 1
			val entriesEnd = entriesStart + content.size
			val embed = source.createEmbed()
				.setTitle("%s / %s".format("Milestones", if (showCompleted) "All" else "Uncompleted"))
				.setDescription("Showing %,d to %,d of %,d entries".format(entriesStart, entriesEnd - 1, entries.size))
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			content.forEach { it.addTo(embed, showCompleted) }
			MessageBuilder(embed)
		}
		return Command.SINGLE_SUCCESS
	}

	private fun fortniteGG(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting challenges")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val payload = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER)
		for (item in athena.items.values) {
			if (item.primaryAssetType != "ChallengeBundle") {
				continue
			}
			val trigger = "_milestone_"
			val milestoneIdx = item.primaryAssetName.indexOf(trigger)
			if (milestoneIdx == -1) {
				continue
			}
			val attrs = item.getAttributes(FortChallengeBundleItem::class.java)
			val bundleDef = item.defData as FortChallengeBundleItemDefinition
			val lastQuestName = bundleDef.QuestInfos.last().QuestDefinition.toString().substringAfterLast('.')
			for (questId in attrs.grantedquestinstanceids) {
				val questItem = athena.items[questId]
				if (questItem != null && questItem.primaryAssetName.equals(lastQuestName, true)) {
					val progress = getQuestCompletion(questItem).first
					payload[item.primaryAssetName.substring(milestoneIdx + trigger.length)] = progress
					break
				}
			}
		}
		if (payload.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No milestone quests detected")).create()
		}
		var url = "https://fortnite.gg/quests?progress=1&" + payload.entries.sortedBy { it.key }.joinToString("&") { it.key + '=' + it.value.toString() }
		url = url.shortenUrl(source)
		source.complete(null, source.createEmbed()
			.setTitle("View your milestones on Fortnite.GG", url)
			.build())
		return Command.SINGLE_SUCCESS
	}

	private class MilestoneEntry : Comparable<MilestoneEntry> {
		val title: String
		var progress = 0 to 0
		val completions = TreeMap<Int, Date?>()

		constructor(item: FortItemStack, athena: McpProfile) {
			val bundleDef = item.defData as? FortChallengeBundleItemDefinition
				?: throw SimpleCommandExceptionType(LiteralMessage("Data not found.")).create()
			val attrs = item.getAttributes(FortChallengeBundleItem::class.java)
			val bundleQuests = hashMapOf<String, FortItemStack>()
			for (questInstanceId in attrs.grantedquestinstanceids) {
				val quest = athena.items[questInstanceId]
					?: throw SimpleCommandExceptionType(LiteralMessage("Referenced quest `$questInstanceId` not found.")).create()
				bundleQuests[quest.primaryAssetName] = quest
			}
			var firstQuest: FortItemStack? = null
			bundleDef.QuestInfos.forEachIndexed { i, questInfo ->
				val primaryAssetName = questInfo.QuestDefinition.toString().substringAfterLast('.').lowercase()
				val quest = bundleQuests[primaryAssetName]
					?: FortItemStack(questInfo.QuestDefinition.load<FortItemDefinition>(), 1)
				if (firstQuest == null) {
					firstQuest = quest
				}
				val progress = getQuestCompletion(quest)
				completions[progress.second] = if (quest.attributes["quest_state"]?.asString == "Claimed") {
					quest.attributes.getDateISO("last_state_change_time")
				} else { // Active
					if (this.progress.second == 0) {
						this.progress = progress
					}
					null
				}
				if (i == bundleDef.QuestInfos.lastIndex && this.progress.second == 0) { // All completed, assign the last one's progress
					this.progress = progress
				}
			}
			title = (firstQuest ?: item).displayName
		}

		fun addTo(embed: EmbedBuilder, showCompleted: Boolean) {
			var highlight = true
			val value = (if (showCompleted) completions.entries else completions.entries.filter { it.value == null }).joinToString(" \u2192 ") { (count, completionDate) ->
				when {
					completionDate != null -> "~~%,d~~ (%s)".format(count, SimpleDateFormat("MMM d").format(completionDate))
					highlight -> {
						highlight = false
						"**%,d / %,d**".format(progress.first, count)
					}
					else -> Formatters.num.format(count)
				}
			}
			embed.addField(if (isCompleted) "✅ ~~$title~~" else title, value, false)
		}

		val isCompleted get() = progress.second > 0 && progress.first >= progress.second

		override fun compareTo(other: MilestoneEntry) = title.compareTo(other.title, true)
	}
}