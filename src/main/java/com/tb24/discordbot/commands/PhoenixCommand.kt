package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.getUsers
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.users
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.QueryPublicProfile
import com.tb24.fn.util.Formatters
import com.tb24.uasset.AssetManager
import me.fungames.jfortniteparse.fort.objects.rows.FortPhoenixLevelRewardData
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder

class PhoenixCommand : BrigadierCommand("ventures", "Shows the given user's venture level, xp, and how much is needed to level up.") {
	private val phoenixLevelRewardsTable by lazy { AssetManager.INSTANCE.provider.loadObject<UDataTable>("/Game/Balance/DataTables/PhoenixLevelRewardsTable.PhoenixLevelRewardsTable") }
	private val noDataErr = SimpleCommandExceptionType(LiteralMessage("No data"))

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Getting Ventures data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
			display(source, source.api.currentLoggedIn)
		}
		.then(literal("rewards")
			.executes { c ->
				val currentEvent = "EventFlag.Phoenix.Winterfest"
				val table = phoenixLevelRewardsTable ?: throw noDataErr.create()
				val levels = table.rows.filterKeys { it.text.startsWith(currentEvent) }.entries.sortedBy { it.key.text.substringAfter(currentEvent).toInt() }.map { it.value.mapToClass<FortPhoenixLevelRewardData>() }.toList()
				c.source.message.replyPaginated(levels, 10) { content, page, pageCount ->
					MessageBuilder(EmbedBuilder()
						.setAuthor("Ventures: Season 3")
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
		.then(argument("user", users(1))
			.executes {
				val source = it.source
				if (source.api.userToken == null) {
					source.session = source.client.internalSession
				}
				val user = getUsers(it, "user").values.first()
				source.loading("Getting Ventures data of ${user.displayName}")
				source.api.profileManager.dispatchPublicCommandRequest(user, QueryPublicProfile(), "campaign").await()
				display(source, user)
			}
		)

	private fun display(source: CommandSourceStack, user: GameProfile): Int {
		val phoenixXpItem = source.api.profileManager.getProfileData(user.id, "campaign").items.values.firstOrNull { it.templateId == "AccountResource:phoenixxp" }
			?: throw SimpleCommandExceptionType(LiteralMessage("no ventures xp item, profile is bugged !!111!!1!")).create()
		val currentEvent = "EventFlag.Phoenix.Winterfest" // TODO don't hardcode this by querying the calendar endpoint
		val table = phoenixLevelRewardsTable ?: throw noDataErr.create()
		val levels = table.rows.filterKeys { it.text.startsWith(currentEvent) }.entries.sortedBy { it.key.text.substringAfter(currentEvent).toInt() }.map { it.value.mapToClass<FortPhoenixLevelRewardData>() }.toList()
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
		val nextLevelData = levels.getOrNull(levelIdx + 1)
		var nextMajorIdx = findMajor(levelIdx + /*next level*/1, levels)
		if (nextMajorIdx == levelIdx + 1) { // next major reward is the next level, find again starting from next level + 1
			nextMajorIdx = findMajor(nextMajorIdx + 1, levels)
		}
		val nextMajorData = levels.getOrNull(nextMajorIdx)
		source.complete(null, source.createEmbed(user)
			.setTitle("Ventures: Season 3")
			.setDescription("**Level ${Formatters.num.format(levelIdx + 1)}** - ${(getItemIconEmoji(phoenixXpItem.templateId)?.run { "$asMention " } ?: "")}${Formatters.num.format(phoenixXpItem.quantity)}\n" + if (nextLevelData != null) {
				val current = phoenixXpItem.quantity - levelData.TotalRequiredXP
				val delta = nextLevelData.TotalRequiredXP - levelData.TotalRequiredXP
				val lastLevel = levels.last()
				"`%s`\n%,d / %,d\n\n%,d XP to next level.\n%,d XP to level %,d.".format(
					Utils.progress(current, delta, 32),
					current, delta,
					nextLevelData.TotalRequiredXP - phoenixXpItem.quantity,
					lastLevel.TotalRequiredXP - phoenixXpItem.quantity,
					levels.size
				)
			} else "Max level.")
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
		return Command.SINGLE_SUCCESS
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