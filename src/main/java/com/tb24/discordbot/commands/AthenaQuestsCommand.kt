package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.ui.QuestsViewController
import com.tb24.discordbot.ui.QuestsViewController.*
import com.tb24.discordbot.util.*
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.format
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.interactions.components.selections.SelectMenu
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import java.util.concurrent.CompletableFuture

class AthenaQuestsCommand : BrigadierCommand("brquests", "Shows your active BR quests.", arrayOf("challenges", "chals")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(argument("category", StringArgumentType.greedyString())
			.executes { execute(it.source, StringArgumentType.getString(it, "category")) }
		)

	private fun execute(source: CommandSourceStack, search: String? = null): Int {
		source.ensureSession()
		var categoryNameToView: String? = null
		val knownCategories = QuestsViewController.getCategories()
		if (search != null) {
			categoryNameToView = knownCategories.search(search.toLowerCase()) { it.DisplayName.format()!! }?.name
				?: throw SimpleCommandExceptionType(LiteralMessage("No matches found for \"$search\". Available options:\n${knownCategories.sortedBy { it.SortOrder }.joinToString("\n") { "\u2022 " + it.DisplayName.format().orDash() }}")).create()
		}
		source.loading("Getting challenges")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")

		val ctx = QuestsViewController(athena, knownCategories)
		if (ctx.categories.isEmpty()) {
			source.complete(null, source.createEmbed().setDescription("No quests").build())
			return Command.SINGLE_SUCCESS
		}

		// Build select menu
		val select = SelectMenu.create("category").setPlaceholder("Pick quest category")
			.addOptions(ctx.categories.map { c ->
				var option = SelectOption.of((c.backing.DisplayName.format() ?: c.backing.name) + " (${c.headers.sumOf { it.quests.size }})", c.backing.name)
				c.backing.CustomTimeRemaining?.let {
					option = option.withDescription(it.format())
				}
				option
			})

		if (categoryNameToView == null) {
			categoryNameToView = select.options.first().value
		}
		while (true) {
			select.setDefaultValues(setOf(categoryNameToView))
			val category = ctx.allCategories[categoryNameToView]!!
			val entries = mutableListOf<Entry>()
			for (goalCard in category.goalCards.values) {
				var first = true
				for (quest in goalCard.quests) {
					entries.add(Entry(quest, null, goalCard, first))
					first = false
				}
			}
			for (header in category.headers) {
				var first = true
				for (quest in header.quests) {
					entries.add(Entry(quest, header, null, first))
					first = false
				}
			}
			if (search != null && entries.isEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage("You have no quests in category ${category.backing.DisplayName.format()}.")).create()
			}
			val nextCategoryEvent = CompletableFuture<String?>()
			source.replyPaginated(entries, 15, customComponents = CategoryPaginatorComponents(select, nextCategoryEvent)) { content, page, pageCount ->
				val entriesStart = page * 15 + 1
				val entriesEnd = entriesStart + content.size
				val embed = source.createEmbed()
					.setTitle("Quests" + " / " + category.backing.DisplayName.format())
				embed.setDescription(content.joinToString("\n") { it.render() }.trim())
				if (pageCount > 1) {
					embed.setDescription("Showing %,d to %,d of %,d entries\n\n%s".format(entriesStart, entriesEnd - 1, entries.size, embed.descriptionBuilder))
						.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				}
				MessageBuilder(embed)
			}
			categoryNameToView = runCatching { nextCategoryEvent.await() }.getOrNull() ?: return Command.SINGLE_SUCCESS
		}
	}

	class Entry(val quest: Quest, val header: QuestCategoryHeader?, val goalCard: GoalCard?, val showHeader: Boolean) {
		fun render(): String {
			return (if (showHeader) {
				if (header != null) {
					"\n__**${header.name.format()}**__\n"
				} else {
					val dd = goalCard!!.displayData
					val tier = dd.MilestoneTier
					"\n" + (tier?.let { textureEmote(GoalCard.Level.fromTier(it).medalTexture)?.asMention + ' ' } ?: "") + "__**" + (tier?.let { "[%,d] ".format(it) } ?: "") + dd.HeaderText.format() + "**__\n" + (goalCard.subHeaderText?.let { "$it\n" } ?: "")
				}
			} else "") + renderChallenge(quest.quest, rewardsPrefix = "\u2800")
		}
	}
}