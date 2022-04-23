package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.QuestCategoryData
import com.tb24.fn.model.assetdata.QuestCategoryData.QuestCategoryHeaderData
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.format
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTag
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTagContainer
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
		val knownCategories = getCategories()
		if (search != null) {
			categoryNameToView = knownCategories.search(search.toLowerCase()) { it.DisplayName.format()!! }?.name
				?: throw SimpleCommandExceptionType(LiteralMessage("No matches found for \"$search\". Available options:\n${knownCategories.sortedBy { it.SortOrder }.joinToString("\n") { "\u2022 " + it.DisplayName.format().orDash() }}")).create()
		}
		source.loading("Getting challenges")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")

		// Gather quests
		val quests = mutableListOf<FortItemStack>()
		for (item in athena.items.values) {
			if (item.primaryAssetType != "Quest") {
				continue
			}
			val defData = item.defData
			if (defData !is FortQuestItemDefinition || defData.bHidden == true || item.attributes["quest_state"]?.asString != "Active") {
				continue
			}
			quests.add(item)
		}

		// Build categories
		val allCategories = hashMapOf<String /*category object name*/, QuestCategory>()
		for (categoryData in knownCategories) {
			allCategories.getOrPut(categoryData.name) { QuestCategory(categoryData) }.addQuests(quests)
		}
		val categories = allCategories.values.filter { it.backing.bAlwaysDisplay || it.headers.isNotEmpty() }.sortedBy { it.backing.SortOrder }
		if (categories.isEmpty()) {
			source.complete(null, source.createEmbed().setDescription("No quests").build())
			return Command.SINGLE_SUCCESS
		}

		// Build select menu
		val select = SelectMenu.create("category").setPlaceholder("Pick quest category")
			.addOptions(categories.map { c -> SelectOption.of((c.backing.DisplayName.format() ?: c.backing.name) + " (${c.headers.sumOf { it.quests.size }})", c.backing.name) })

		/*quests.sortWith { a, b ->
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
		}*/

		if (categoryNameToView == null) {
			categoryNameToView = select.options.first().value
		}
		while (true) {
			select.setDefaultValues(setOf(categoryNameToView))
			val category = allCategories[categoryNameToView]!!
			class Entry(val header: QuestCategoryHeader?, val quest: FortItemStack)
			val entries = mutableListOf<Entry>()
			for (header in category.headers) {
				var first = true
				for (quest in header.quests) {
					if (first) {
						entries.add(Entry(header, quest))
						first = false
					} else {
						entries.add(Entry(null, quest))
					}
				}
			}
			if (search != null && entries.isEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage("You have no quests in category ${category.backing.DisplayName.format()}.")).create()
			}
			val nextCategoryEvent = CompletableFuture<String?>()
			source.replyPaginated(entries, 15, customComponents = CategoryPaginatorComponents(select, nextCategoryEvent)) { content, page, pageCount ->
				val entriesStart = page * 15 + 1
				val entriesEnd = entriesStart + content.size
				val value = content.joinToString("\n") {
					(if (it.header != null) "\n__**${it.header.name.format()}**__\n" else "") + renderChallenge(it.quest, rewardsPrefix = "\u2800")
				}.trim()
				val embed = source.createEmbed()
					.setTitle("Quests" + " / " + category.backing.DisplayName.format())
					.setDescription(value)
				if (pageCount > 1) {
					embed.setDescription("Showing %,d to %,d of %,d entries\n\n%s".format(entriesStart, entriesEnd - 1, entries.size, embed.descriptionBuilder))
						.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				}
				MessageBuilder(embed)
			}
			categoryNameToView = runCatching { nextCategoryEvent.await() }.getOrNull() ?: return Command.SINGLE_SUCCESS
		}
	}

	private fun getCategories() = AssetManager.INSTANCE.assetRegistry.templateIdToAssetDataMap.values.filter { it.assetClass == "QuestCategoryData" }.map { loadObject<QuestCategoryData>(it.objectPath)!! }

	class QuestCategory(val backing: QuestCategoryData) {
		private val allHeaders = mutableListOf<QuestCategoryHeader>()
		private val defaultHeader: QuestCategoryHeader

		init {
			backing.AdditionalHeaders?.forEach {
				if (it.bDisplayAboveDefaultHeader) {
					allHeaders.add(QuestCategoryHeader(it))
				}
			}
			allHeaders.add(QuestCategoryHeader(backing.DefaultHeaderName).also { defaultHeader = it })
			backing.AdditionalHeaders?.forEach {
				if (!it.bDisplayAboveDefaultHeader) {
					allHeaders.add(QuestCategoryHeader(it))
				}
			}
		}

		fun addQuests(quests: List<FortItemStack>) {
			for (quest in quests) {
				// Determine if quest should be included in this category
				val questDef = quest.defData as FortQuestItemDefinition
				val tags = questDef.GameplayTags ?: FGameplayTagContainer()
				val shouldIncludeByIncludeTags = backing.IncludeTags == null || backing.IncludeTags.any { tags.getValue(it.toString()) != null }
				val shouldIncludeByExcludeTags = backing.ExcludeTags == null || backing.ExcludeTags.none { tags.getValue(it.toString()) != null }
				if (!shouldIncludeByIncludeTags || !shouldIncludeByExcludeTags) {
					continue
				}

				// Determine which subcategory (header) this quest belongs to
				val header = allHeaders.firstOrNull { tags.getValue(it.tag.toString()) != null } ?: defaultHeader

				// Add quest to subcategory
				header.quests.add(quest)
			}
		}

		private var _headers: List<QuestCategoryHeader>? = null
		val headers: List<QuestCategoryHeader>
			get() {
				if (_headers == null) {
					_headers = allHeaders.filter { it.quests.isNotEmpty() }
				}
				return _headers!!
			}
	}

	class QuestCategoryHeader(val name: FText) {
		var tag: FGameplayTag? = null
		val quests = mutableListOf<FortItemStack>()

		constructor(backing: QuestCategoryHeaderData) : this(backing.HeaderName) {
			tag = backing.HeaderTag
		}
	}
}