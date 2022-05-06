package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.ItemArgument
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.replyPaginated
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.format
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.components.selections.SelectMenu
import java.util.concurrent.CompletableFuture

class CampaignQuestsCommand : BrigadierCommand("quests", "STW quest log.", arrayOf("quest")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::categories, "Getting quests")
		.then(literal("detail")
			.then(argument("quest", ItemArgument.item(true))
				.executes {
					val source = it.source
					source.ensureSession()
					source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
					val campaign = source.api.profileManager.getProfileData("campaign")
					questDetails(source, ItemArgument.getItem(it, "quest", campaign, "Quest"))
				}
			)
		)

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("view", description)
			.withPublicProfile(::categories, "Getting quests")
		)
		.then(subcommand("detail", "Show details of a quest.")
			.option(OptionType.STRING, "quest", "Quest name", true, argument = ItemArgument.item(true))
			.executes { source ->
				source.ensureSession()
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
				val campaign = source.api.profileManager.getProfileData("campaign")
				questDetails(source, source.getArgument<ItemArgument.Result>("quest")!!.resolve(campaign, "Quest"))
			}
		)

	private fun categories(source: CommandSourceStack, campaign: McpProfile): Int {
		val questsByCategory = campaign.items.values.filter { it.primaryAssetType == "Quest" && it.attributes["quest_state"]?.asString == "Active" }.groupBy {
			val handle = (it.defData as? FortQuestItemDefinition)?.Category
			handle?.rowName?.toString() to handle?.row
		}
		val select = SelectMenu.create("category").setPlaceholder("Pick quest category")
		questsByCategory.entries.sortedByDescending { it.key.second?.get<Int>("SortPriority") ?: 0 }.forEach { (categoryPair, quests) ->
			val (categoryName, category) = categoryPair
			select.addOption((category?.get<FText>("Name")?.format() ?: "Unknown") + " (${quests.size})", categoryName ?: "Unknown")
		}
		if (select.options.isEmpty()) {
			source.complete(null, source.createEmbed(campaign.owner).setDescription("No quests").build())
			return Command.SINGLE_SUCCESS
		}
		var categoryNameToView = select.options.first().value
		while (true) {
			select.setDefaultValues(setOf(categoryNameToView))
			val entries = questsByCategory.entries.first { it.key.first == categoryNameToView }.value
			val nextCategoryEvent = CompletableFuture<String?>()
			source.replyPaginated(entries, 15, customComponents = CategoryPaginatorComponents(select, nextCategoryEvent)) { content, page, pageCount ->
				val entriesStart = page * 15 + 1
				val entriesEnd = entriesStart + content.size
				val embed = categoryEmbed(source, campaign, categoryNameToView, entries)
				if (pageCount > 1) {
					embed.setDescription("Showing %,d to %,d of %,d entries\n\n%s".format(entriesStart, entriesEnd - 1, entries.size, embed.descriptionBuilder))
						.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				}
				MessageBuilder(embed)
			}
			categoryNameToView = runCatching { nextCategoryEvent.await() }.getOrNull() ?: return Command.SINGLE_SUCCESS
		}
	}

	private fun categoryEmbed(source: CommandSourceStack, campaign: McpProfile, categoryNameToView: String, quests: List<FortItemStack> = getQuestsOfCategory(campaign, categoryNameToView)): EmbedBuilder {
		val canReceiveMtxCurrency = campaign.items.values.any { it.templateId == "Token:receivemtxcurrency" }
		val category = questCategoryTable.findRow(categoryNameToView)
		return source.createEmbed(campaign.owner)
			.setTitle(category?.get<FText>("Name")?.format() ?: "Unknown")
			.setDescription(quests.joinToString("\n") {
				renderChallenge(it, rewardsPrefix = "\u2800", conditionalCondition = canReceiveMtxCurrency)
			})
	}

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