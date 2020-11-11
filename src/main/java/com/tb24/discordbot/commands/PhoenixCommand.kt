@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.FortPhoenixLevelRewardData
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.Formatters
import com.tb24.uasset.AssetManager
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder

class PhoenixCommand : BrigadierCommand("ventures", "Shows the given user's venture level, xp, and how much is needed to level up.") {
	private val phoenixLevelRewardsTable by lazy { AssetManager.INSTANCE.provider.loadObject<UDataTable>("/Game/Balance/DataTables/PhoenixLevelRewardsTable.PhoenixLevelRewardsTable") }
	private val noDataErr = SimpleCommandExceptionType(LiteralMessage("No data"))

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting Ventures data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
			val phoenixXpItem = source.api.profileManager.getProfileData("campaign").items.values.firstOrNull { it.templateId == "AccountResource:phoenixxp" }
				?: throw SimpleCommandExceptionType(LiteralMessage("no ventures xp item, profile is bugged !!111!!1!")).create()
			val currentEvent = "EventFlag.Phoenix.Fortnitemares"
			val table = phoenixLevelRewardsTable ?: throw noDataErr.create()
			val levels = table.rows.filterKeys { it.text.startsWith(currentEvent) }.values.map { it.mapToClass<FortPhoenixLevelRewardData>() }.toList()
			var levelData: FortPhoenixLevelRewardData? = null
			var levelIdx = levels.size
			while (levelIdx-- > 0) {
				if (phoenixXpItem.quantity >= levels[levelIdx].TotalRequiredXP) {
					levelData = levels[levelIdx]
					break
				}
			}
			if (levelData == null) {
				throw SimpleCommandExceptionType(LiteralMessage("Dunno what level dude, idk anymore")).create()
			}
			val nextLevelData = if (levelIdx + 1 < levels.size) levels[levelIdx + 1] else null
			var nextMajorData: FortPhoenixLevelRewardData? = null
			var nextMajorIdx = levelIdx + /*next level*/1
			while (nextMajorIdx < levels.size) {
				val entry = levels[nextMajorIdx]
				if (entry.bIsMajorReward) {
					nextMajorData = entry
					break
				}
				++nextMajorIdx
			}
			source.complete(null, source.createEmbed()
				.setTitle("Ventures: Season 2")
				.setDescription("Level ${Formatters.num.format(levelIdx + 1)} - ${(getItemIconEmote(phoenixXpItem.templateId)?.run { "$asMention " } ?: "")}${Formatters.num.format(phoenixXpItem.quantity)}\n" + if (nextLevelData != null) {
					val current = phoenixXpItem.quantity - levelData.TotalRequiredXP
					val delta = nextLevelData.TotalRequiredXP - levelData.TotalRequiredXP
					"`${progress(current, delta)}`\n${Formatters.num.format(nextLevelData.TotalRequiredXP - phoenixXpItem.quantity)} XP to next level."
				} else "")
				.apply {
					if (nextLevelData != null) {
						addField("Rewards for level ${Formatters.num.format(levelIdx + /*next level*/1 + /*index offset*/1)}", nextLevelData.VisibleReward.joinToString("\n") { FortItemStack(it.TemplateId, it.Quantity).renderWithIcon() }, true)
					}
				}
				.apply {
					if (nextMajorData != null && nextMajorData != nextLevelData) {
						addField("Rewards for level ${Formatters.num.format(nextMajorIdx + /*index offset*/1)}", nextMajorData.VisibleReward.joinToString("\n") { FortItemStack(it.TemplateId, it.Quantity).renderWithIcon() }, true)
					}
				}
				.build())
			Command.SINGLE_SUCCESS
		}
		.then(literal<CommandSourceStack>("rewards")
			.executes { c ->
				val currentEvent = "EventFlag.Phoenix.Fortnitemares"
				val table = phoenixLevelRewardsTable ?: throw noDataErr.create()
				val levels = table.rows.filterKeys { it.text.startsWith(currentEvent) }.values.map { it.mapToClass<FortPhoenixLevelRewardData>() }.toList()
				c.source.message.replyPaginated(levels, 10) { content, page, pageCount ->
					MessageBuilder(EmbedBuilder()
						.setAuthor("Ventures: Season 2")
						.setTitle("Rewards")
						.apply {
							content.forEachIndexed { i, entry ->
								val listIndex = page * 10 + i
								addField("Level ${Formatters.num.format(listIndex + 1)}${(if (listIndex < levels.size - 1) " (${Formatters.num.format(levels[listIndex + 1].TotalRequiredXP - entry.TotalRequiredXP)} XP)" else "")}", entry.VisibleReward.joinToString("\n") { FortItemStack(it.TemplateId, it.Quantity).renderWithIcon() }.takeIf { it.isNotEmpty() } ?: "No rewards", true)
							}
						}
						.setFooter("Page %d of %d".format(page + 1, pageCount))
					).build()
				}
				Command.SINGLE_SUCCESS
			}
		)

	private fun progress(current: Int, max: Int, width: Int = 32): String {
		val barWidth: Int = width - 2
		val ratio = current.toFloat() / max.toFloat()
		val barEnd = (ratio * barWidth + 0.5f).toInt()
		val sb: StringBuilder = StringBuilder(width)
		sb.append('[')
		for (i in 0 until barWidth) {
			sb.append(if (i >= barEnd) ' ' else if (i == barEnd - 1) '>' else '=')
		}
		sb.append(']')
		return sb.toString()
	}
}
