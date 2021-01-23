package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.QueryMultipleUserStats
import com.tb24.fn.model.assetdata.FortQuestIndicatorData
import com.tb24.fn.model.assetdata.GameDataBR
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.CollectionAttributes
import com.tb24.fn.model.mcpprofile.item.CollectionAttributes.*
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.uasset.AssetManager.INSTANCE
import me.fungames.jfortniteparse.fort.exports.*
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import java.util.concurrent.CompletableFuture

private val defaultGameDataBR by lazy { INSTANCE.provider.loadObject<GameDataBR>("/Game/Balance/DefaultGameDataBR.DefaultGameDataBR") }
private val questIndicatorData by lazy { INSTANCE.provider.loadObject<FortQuestIndicatorData>("/Game/Quests/QuestIndicatorData.QuestIndicatorData") }
private val locationTagToDisplayName by lazy {
	val map = hashMapOf<String, FText>()
	questIndicatorData?.apply {
		ChallengeMapPoiData.associateTo(map) { it.LocationTag.toString().toLowerCase() to it.Text }
	}
	map
}
private val seasonNum = 15

class CharacterCollectionCommand : BrigadierCommand("charactercollection", "Shows your character collection.", arrayOf("characters", "npcs")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it, ECharCollectionSubCommand.PaginatedDetails) }
		.then(literal("all").executes { execute(it, ECharCollectionSubCommand.PaginatedDetailsAll) })
		.then(literal("summary").executes { execute(it, ECharCollectionSubCommand.Summary) })
		.then(literal("leaderboard").executes { leaderboard(it.source) })

	private fun execute(context: CommandContext<CommandSourceStack>, type: ECharCollectionSubCommand): Int {
		val source = context.source
		source.ensureSession()
		source.loading("Getting character collection data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "collections").await()
		val collections = source.api.profileManager.getProfileData("collections")
		val collected = mutableListOf<FortMcpCollectedItemProperties>()
		for (item in collections.items.values) {
			if (item.primaryAssetType == "CollectableCharacter") {
				collected.addAll(item.getAttributes(CollectionAttributes::class.java).collected)
			}
		}
		if (type == ECharCollectionSubCommand.PaginatedDetails && collected.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You've never caught any fish yet. Use `${source.prefix}${context.commandName} all` to show all entries.")).create()
		}
		val seasonData = FortItemStack("AthenaSeason:athenaseason$seasonNum", 1).defData as? AthenaSeasonItemDefinition
			?: throw SimpleCommandExceptionType(LiteralMessage("Season data not found.")).create()
		val data = seasonData.CollectionsDataTable.load<FortCollectionsDataTable>()?.Collections
			?.firstOrNull { it.CollectionType == "CollectableCharacter" }?.Collection?.load<FortCollectionDataCharacter>()?.Entries
			?.mapIndexed { i, it ->
				val entry = it.value as FortCollectionDataEntryCharacter
				val characterData = entry.CharacterData.load<FortTandemCharacterData>()
					?: throw SimpleCommandExceptionType(LiteralMessage("Data for ${entry.CharacterData.toString().substringAfterLast('.')} not found.")).create()
				CharacterEntry(characterData, characterData.GameplayTag.toString().run { collected.firstOrNull { it.variantTag == this } }, i)
			} ?: throw SimpleCommandExceptionType(LiteralMessage("Character collection data not found.")).create()
		if (type == ECharCollectionSubCommand.Summary) {
			val title = "Characters / Summary"
			var embed = source.createEmbed().setTitle(title)
			var completed = 0
			for ((i, entry) in data.withIndex()) {
				if (i % 24 == 0 && i / 24 > 0) {
					source.complete(null, embed.build())
					embed = EmbedBuilder().setTitle("$title (continued)").setColor(COLOR_SUCCESS)
				}
				val def = entry.def
				val collectedProps = entry.collectedProps
				appendCharacterLocations(def, collectedProps, embed, "%,d. %s".format(i + 1, def.DisplayName.format() ?: def.name), true)
				if (collectedProps?.seenState == EFortCollectedState.Complete) {
					++completed
				}
			}
			embed.setFooter("%,d of %,d completed".format(completed, data.size))
			source.complete(null, embed.build())
			return Command.SINGLE_SUCCESS
		}
		source.message.replyPaginated(if (type == ECharCollectionSubCommand.PaginatedDetails) data else data.filter { it.collectedProps != null && it.collectedProps.seenState != EFortCollectedState.New }, 1, source.loadingMsg) { content, page, pageCount ->
			val entry = content.first()
			val def = entry.def
			val collectedProps = entry.collectedProps
			val idx = entry.idx
			val embed = EmbedBuilder()
				.setAuthor('#' + Formatters.num.format(idx + 1))
				.setTitle(def.DisplayName.format())
				.setDescription("**%s**\n%s".format(def.GeneralDescription.format(), def.AdditionalDescription.format()))
				.setImage(Utils.benBotExportAsset(def.SidePanelIcon.toString()))
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				.setColor(def.name.hashCode())
			appendCharacterLocations(def, collectedProps, embed, "Found near", false)
			collectedProps?.apply {
				val properties = EpicApi.GSON.fromJson(properties, FortMcpCollectedCharacterProperties::class.java)
				embed.addField("State", seenState.name, false)
				if (seenState != EFortCollectedState.New) {
					embed.addField("Times interacted", '\u00d7' + Formatters.num.format(count), true)
					embed.addField("Quests given", Formatters.num.format(properties.questsGiven), true)
					embed.addField("Quests completed", Formatters.num.format(properties.questsCompleted), true)
				}
			}
			MessageBuilder(embed).build()
		}
		return Command.SINGLE_SUCCESS
	}

	private fun leaderboard(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting character collection scores")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "collections").await()
		val collections = source.api.profileManager.getProfileData("collections")
		var selfScore = 0
		for (item in collections.items.values) {
			if (item.primaryAssetType == "CollectableCharacter") {
				selfScore += item.getAttributes(CollectionAttributes::class.java).collected.count { it.seenState == EFortCollectedState.Complete }
			}
		}
		val self = source.api.currentLoggedIn
		val friends = source.api.friendsService.queryFriends(self.id, true).exec().body()!!
		val stats = friends.map { it.accountId }
			.chunked(50)
			.map { source.api.statsproxyService.queryMultipleUserStats("collection_character", QueryMultipleUserStats().apply { owners = it.toTypedArray() }).future() }
			.apply { CompletableFuture.allOf(*toTypedArray()).await() }
			.flatMap { it.get().body()!!.toList() }
		val embed = EmbedBuilder().setTitle("Characters / Friends leaderboard").setColor(COLOR_SUCCESS)
		val scores = mutableMapOf<String, Int>(self.id to selfScore) // account ID -> characters completed
		stats.associateTo(scores) { it.accountId to (it.stats["br_collection_character_count_s15"] ?: 0) }
		val iterator = scores.entries.sortedWith { a, b ->
			if (a.value != b.value) {
				b.value - a.value
			} else {
				a.key.compareTo(b.key)
			}
		}.iterator()
		val sb = StringBuilder()
		var placement = 0
		var last = 0
		var i = 0
		while (iterator.hasNext()) {
			val e = iterator.next()
			if (e.value <= 0) {
				continue
			}
			if (last != e.value) {
				++placement
				last = e.value
			}
			val dn: String
			val bold: String
			if (e.key == self.id) {
				dn = self.displayName
				bold = "**"
			} else {
				dn = friends.firstOrNull { it.accountId == e.key }?.displayName ?: e.key
				bold = ""
			}
			sb.append("%s#%,d %s \u2014 %,d%s".format(bold, placement, dn.escapeMarkdown(), e.value, bold))
			if (iterator.hasNext()) {
				sb.append('\n')
				if (i + 1 == 50) {
					sb.append("... ${scores.size - 50} more entries ...")
					break
				}
			}
			++i
		}
		embed.addField("Characters completed", sb.toString(), false)
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun appendCharacterLocations(def: FortTandemCharacterData, collectedProps: FortMcpCollectedItemProperties?, embed: EmbedBuilder, title: String, inline: Boolean) {
		val iterator = def.POILocations.iterator()
		val sb = StringBuilder()
		var visited = 0
		var i = 0
		while (iterator.hasNext()) {
			val tag = iterator.next().toString()
			var poiName = (def.POITextOverrides?.getOrNull(i) ?: locationTagToDisplayName[tag.toLowerCase().replace(".tandem", "")])?.format()
			if (poiName.isNullOrEmpty()) {
				poiName = tag
			}
			sb.append(if (collectedProps != null && collectedProps.contextTags.any { it == tag }) {
				++visited
				"\\☑ ~~$poiName~~"
			} else {
				"☐ $poiName"
			})
			if (iterator.hasNext()) {
				sb.append('\n')
			}
			++i
		}
		val num = def.POILocations.gameplayTags.size
		embed.addField("%s (%,d/%,d)".format(title, visited, num), sb.toString(), inline)
	}

	enum class ECharCollectionSubCommand {
		PaginatedDetails,
		PaginatedDetailsAll,
		Summary
	}

	class CharacterEntry(val def: FortTandemCharacterData, val collectedProps: FortMcpCollectedItemProperties?, val idx: Int)
}

class FishCollectionCommand : BrigadierCommand("fishcollection", "Shows your fish collection.", arrayOf("fishing", "collections")) {
	private val itemDefToItemVariantMapping by lazy { defaultGameDataBR?.ItemDefToItemVariantDataMappingAsset?.load<FortItemDefToItemVariantDataMapping>()?.ItemDefToItemVariantDataMappings }
	private val fishTagToItemVariantDataMapping by lazy { itemDefToItemVariantMapping?.flatMap { it.ItemVariantData.value.Variants }?.associateBy { it.CollectionTag.toString() } }

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes(::execute)
		.then(literal("all")
			.executes { execute(it, true) }
		)

	private fun execute(context: CommandContext<CommandSourceStack>, all: Boolean = false): Int {
		val source = context.source
		source.ensureSession()
		source.loading("Getting fishing collection and friends leaderboard data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "collections").await()
		val collections = source.api.profileManager.getProfileData("collections")
		val collected = mutableListOf<FortMcpCollectedItemProperties>()
		for (item in collections.items.values) {
			if (item.primaryAssetType == "CollectableFish") {
				collected.addAll(item.getAttributes(CollectionAttributes::class.java).collected)
			}
		}
		if (!all && collected.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You've never caught any fish yet. Use `${source.prefix}${context.commandName} all` to show all entries.")).create()
		}
		val seasonData = FortItemStack("AthenaSeason:athenaseason$seasonNum", 1).defData as? AthenaSeasonItemDefinition
			?: throw SimpleCommandExceptionType(LiteralMessage("Season data not found.")).create()
		val data = seasonData.CollectionsDataTable.load<FortCollectionsDataTable>()?.Collections
			?.firstOrNull { it.CollectionType == "CollectableFish" }?.Collection?.load<FortCollectionDataFishing>()?.Entries
			?.mapIndexed { i, it -> it.value.run { FishEntry(this as FortCollectionDataEntryFish, EntryTag.TagName.run { collected.firstOrNull { it.variantTag == text } }, i) } }
			?: throw SimpleCommandExceptionType(LiteralMessage("Fishing collection data not found.")).create()
		val self = source.api.currentLoggedIn
		val friends = source.api.friendsService.queryFriends(self.id, true).exec().body()!!
		val stats = friends.map { it.accountId }
			.chunked(50)//55)
			.map { source.api.statsproxyService.queryMultipleUserStats("collection_fish", QueryMultipleUserStats().apply { owners = it.toTypedArray() }).future() }
			.apply { CompletableFuture.allOf(*toTypedArray()).await() }
			.flatMap { it.get().body()!!.toList() }
		source.message.replyPaginated(if (all) data else data.filter { it.collectedProps != null && it.collectedProps.seenState != EFortCollectedState.New }, 1, source.loadingMsg) { content, page, pageCount ->
			val entry = content.first()
			val def = entry.def
			val collectedProps = entry.collectedProps
			val idx = entry.idx
			val embed = EmbedBuilder()
				.setAuthor('#' + Formatters.num.format(idx + 1))
				.setTitle(def.EntryName.format())
				.setDescription(def.EntryDescription.format())
				.setThumbnail(Utils.benBotExportAsset(def.LargeIcon.toString()))
				.addField("Hint", def.AdditionalEntryDescription.format()!!.replace("<text color=\"fff\" fontface=\"black\" case=\"upper\">", "**").replace("</>", "**"), false)
				.addField("Size", def.Size.getValue0().toString(), false)
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				.setColor(def.EntryTag.toString().hashCode())
			fishTagToItemVariantDataMapping?.get(def.EntryTag.toString())?.apply {
				if (POITags.gameplayTags.isNotEmpty()) {
					embed.addFieldSeparate("POI tags", POITags.toList(), 0, true)
				}
				if (TODTags.gameplayTags.isNotEmpty()) {
					embed.addFieldSeparate("Time of day tags", TODTags.toList(), 0, true)
				}
			}
			val scores = mutableMapOf<String, Float>() // account ID -> length in cm
			collectedProps?.apply {
				val properties = EpicApi.GSON.fromJson(properties, FortMcpCollectedFishProperties::class.java)
				scores[self.id] = properties.length
				embed.addField("State", seenState.name, false)
				if (seenState != EFortCollectedState.New) {
					embed.addField("Times caught", '\u00d7' + Formatters.num.format(count), true)
					embed.addField("Personal best", "%s %.2f cm".format("\u2605".repeat(properties.weight), properties.length), true)
				}
			}

			// Fish.EffectiveFlopper.Purple
			val split = def.EntryTag.toString().split('.')
			// br_{type}         _{category}      _{variant}_{metric}_s{season}
			// br_collection_fish_effectiveflopper_purple   _length  _s14
			val statKey = "br_${"collection_fish"}_${split[1]}_${split[2]}_${"length"}_s$seasonNum".toLowerCase()
			stats.associateTo(scores) { it.accountId to (it.stats[statKey] ?: 0) / 1000f }
			embed.addField("Scores", scores.entries
				.filter { it.value > 0f }
				.sortedByDescending { it.value }
				.mapIndexed { i, e ->
					val dn: String
					val bold: String
					if (e.key == self.id) {
						dn = self.displayName
						bold = "**"
					} else {
						dn = friends.firstOrNull { it.accountId == e.key }?.displayName ?: e.key
						bold = ""
					}
					"%s#%,d %s %.2f cm%s".format(bold, i + 1, dn.escapeMarkdown(), e.value, bold)
				}
				.joinToString("\n", limit = 10, truncated = ""), false)
			MessageBuilder(embed).build()
		}
		return Command.SINGLE_SUCCESS
	}

	class FishEntry(val def: FortCollectionDataEntryFish, val collectedProps: FortMcpCollectedItemProperties?, val idx: Int)
}