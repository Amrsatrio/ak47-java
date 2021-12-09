package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.SEASON_NUM
import com.tb24.discordbot.images.MapImageGenerator
import com.tb24.discordbot.images.MapImageGenerator.MapMarker
import com.tb24.discordbot.images.MapImageGenerator.MapPath
import com.tb24.discordbot.images.MapImageGenerator.MapPath.EPathOp
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.QueryMultipleUserStats
import com.tb24.fn.model.assetdata.*
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.FortMcpCollectionBase
import com.tb24.fn.model.mcpprofile.item.FortMcpCollectionBase.*
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.uasset.AssetManager.INSTANCE
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.*
import me.fungames.jfortniteparse.ue4.assets.exports.UWorld
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.assets.objects.UScriptArray
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import me.fungames.jfortniteparse.ue4.objects.core.math.FBox
import me.fungames.jfortniteparse.ue4.objects.core.math.FVector2D
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTagContainer
import me.fungames.jfortniteparse.ue4.objects.uobject.FPackageIndex
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.font.TextLayout
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess

private val defaultGameDataBR by lazy { loadObject<GameDataBR>("/Game/Balance/DefaultGameDataBR.DefaultGameDataBR") }
private val questIndicatorData by lazy { loadObject<FortQuestIndicatorData>("/Game/Quests/QuestIndicatorData.QuestIndicatorData") }
private val locationTagToDisplayName by lazy {
	val map = hashMapOf<String, FText>()
	questIndicatorData?.apply {
		ChallengeMapPoiData.associateTo(map) { it.LocationTag.toString().toLowerCase() to it.Text }
	}
	map
}

class CharacterCollectionCommand : BrigadierCommand("charactercollection", "Shows your character collection.", arrayOf("characters", "npcs")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it, ECharCollectionSubCommand.PaginatedDetails) }
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
				collected.addAll(item.getAttributes(FortMcpCollectionBase::class.java).collected)
			}
		}
		val seasonData = FortItemStack("AthenaSeason:athenaseason$SEASON_NUM", 1).defData as? AthenaSeasonItemDefinition
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
					embed = EmbedBuilder().setTitle("$title (continued)").setColor(COLOR_INFO)
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
		source.message.replyPaginated(data, 1, source.loadingMsg) { content, page, pageCount ->
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
				selfScore += item.getAttributes(FortMcpCollectionBase::class.java).collected.count { it.seenState == EFortCollectedState.Complete }
			}
		}
		val self = source.api.currentLoggedIn
		val friends = source.api.friendsService.queryFriends(self.id, true).exec().body()!!
		val accountIds = friends.map { it.accountId }
		val stats = accountIds
			.chunked(50)
			.map { source.api.statsproxyService.queryMultipleUserStats("collection_character", QueryMultipleUserStats().apply { owners = it.toTypedArray() }).future() }
			.apply { CompletableFuture.allOf(*toTypedArray()).await() }
			.flatMap { it.get().body()!!.toList() }
		source.queryUsers_map(accountIds)
		val embed = EmbedBuilder().setTitle("Characters / Friends leaderboard").setColor(COLOR_INFO)
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
				dn = source.userCache[e.key]?.displayName ?: e.key
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
			sb.append(if (collectedProps != null && tag in collectedProps.contextTags) {
				++visited
				"`☑` ~~$poiName~~"
			} else {
				"`☐` $poiName"
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
		Summary
	}

	class CharacterEntry(val def: FortTandemCharacterData, val collectedProps: FortMcpCollectedItemProperties?, val idx: Int)
}

class FishCollectionCommand : BrigadierCommand("fishcollection", "Shows your fish collection.", arrayOf("fishing", "fish")) {
	private val itemDefToItemVariantMapping by lazy { defaultGameDataBR?.ItemDefToItemVariantDataMappingAsset?.load<FortItemDefToItemVariantDataMapping>()?.ItemDefToItemVariantDataMappings }
	private val fishTagToItemVariantDataMapping by lazy { itemDefToItemVariantMapping?.flatMap { it.ItemVariantData.value.Variants }?.associateBy { it.CollectionTag.toString() } }

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes(::execute)

	private fun execute(context: CommandContext<CommandSourceStack>): Int {
		val source = context.source
		source.ensureSession()
		source.loading("Getting fishing collection and friends leaderboard data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "collections").await()
		val collections = source.api.profileManager.getProfileData("collections")
		val collected = mutableListOf<FortMcpCollectedItemProperties>()
		for (item in collections.items.values) {
			if (item.primaryAssetType == "CollectableFish") {
				collected.addAll(item.getAttributes(FortMcpCollectionBase::class.java).collected)
			}
		}
		val seasonData = FortItemStack("AthenaSeason:athenaseason$SEASON_NUM", 1).defData as? AthenaSeasonItemDefinition
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
		source.message.replyPaginated(data, 1, source.loadingMsg) { content, page, pageCount ->
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
			val statKey = "br_${"collection_fish"}_${split[1]}_${split[2]}_${"length"}_s$SEASON_NUM".toLowerCase()
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
						dn = source.userCache[e.key]?.displayName ?: e.key
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

fun main() {
	INSTANCE.loadPaks()
	val tandems = loadObject<FortCollectionDataCharacter>("/BattlepassS$SEASON_NUM/SeasonData/CollectionDataCharacter")!!.Entries.map { (it.value as FortCollectionDataEntryCharacter).CharacterData.load<FortTandemCharacterData>()!! }
	val actors = arrayOf(
		"/NPCLibrary/LevelOverlays/Apollo_Terrain_NPCLibrary_Overlay",
		"/NPCLibrary/LevelOverlays/Apollo_Terrain_NPCLibraryBoss_Overlay",
		"/NPCLibrary/LevelOverlays/Apollo_Terrain_NPCLibrary_Overlay"
	).flatMap {
		val world = loadObject<UWorld>(it)!!
		val persistentLevel = world.persistentLevel!!.value
		persistentLevel.actors.toList()
	}
	val map = MapImageGenerator()
	val f = Font.createFont(Font.TRUETYPE_FONT, File("C:\\Users\\satri\\AppData\\Local\\Microsoft\\Windows\\Fonts\\zh-cn.ttf")).deriveFont(26f)
	val noTagsPatrolPaths = mutableMapOf<String, FortAthenaPatrolPath>()
	val patrolPathsWithTagsFromOtherObject = mutableMapOf<String, FortAthenaPatrolPath>()
	for (actorLazy in actors) {
		val actor = actorLazy?.value ?: continue
		if (actor is FortAthenaPatrolPath) {
			if (actor.GameplayTags != null) {
				addPatrolPath(actor, actor.GameplayTags, map, tandems, f)
			} else {
				noTagsPatrolPaths[actor.name] = actor
			}
		} else if (actor.exportType == "BP_CalendarProvider_MultiSpot_C") {
			// TODO honor calendar event name
			// val calendarPointProviders = actor.getProp<List<Lazy<FortAthenaPatrolPathPointProvider>>>("CalendarPointProviders", object : TypeToken<List<Lazy<FortAthenaPatrolPathPointProvider>>>() {}.type)
			val calendarPointProviders = actor.get<UScriptArray>("CalendarPointProviders")
			calendarPointProviders.contents.forEach {
				val value = it.getTagTypeValueLegacy() as FPackageIndex
				val calendarPointProvider = value.load<FortAthenaPatrolPathPointProvider>()!!
				if (calendarPointProvider.FiltersTags != null) {
					val associatedPath = calendarPointProvider.AssociatedPatrolPath.value
					addPatrolPath(associatedPath, calendarPointProvider.FiltersTags, map, tandems, f)
					patrolPathsWithTagsFromOtherObject[associatedPath.name] = associatedPath
				}
			}
		}
	}
	for (it in noTagsPatrolPaths.values) {
		if (it.name !in patrolPathsWithTagsFromOtherObject) {
			addPatrolPath(it, null, map, tandems, f)
		}
	}
	File("npc_map_16.00.png").writeBytes(map.draw().toPngArray())
	exitProcess(0)
}

private fun addPatrolPath(patrolPath: FortAthenaPatrolPath, tags: FGameplayTagContainer?, map: MapImageGenerator, tandems: List<FortTandemCharacterData>, f: Font) {
	val path = object : MapPath(EPathStyle.Stroke) {
		override fun preDraw(ctx: Graphics2D) {
			ctx.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_BEVEL)
			ctx.color = 0xFFF300.awtColor()
		}
	}
	val bounds = FBox()
	for ((i, patrolPoint_) in patrolPath.PatrolPoints.withIndex()) {
		val patrolPoint = patrolPoint_?.value ?: continue
		val sceneComp = patrolPoint.RootComponent.value
		val rl = sceneComp.RelativeLocation
		path.ops.add(MapPath.Op(if (i == 0) EPathOp.Move else EPathOp.Line, FVector2D(rl.x, rl.y)))
		bounds += rl
	}
	map.paths.add(path)
	val center3d = bounds.getCenter()
	val center = FVector2D(bounds.max.x/*center3d.x*/, center3d.y)
	val tandem = tags?.let { spawnerTagsToTandem(it, tandems) }
	val text = tandem?.DisplayName.format() ?: patrolPath.name.replace("FortAthenaPatrolPath_", "")
	val pic = tandem?.ToastIcon?.load<UTexture2D>()
	map.markers.add(MapMarker(center) { ctx, mx, my ->
		ctx.font = f
		val metrics = ctx.getFontMetrics(f)
		val x = mx - metrics.stringWidth(text) / 2
		val y = my - metrics.height // / 2
		val oldTransform = ctx.transform
		ctx.translate(x, y + metrics.ascent)
		ctx.color = 0x7F000000.awtColor()
		ctx.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
		ctx.draw(TextLayout(text, f, ctx.fontRenderContext).getOutline(null))
		ctx.color = Color.WHITE
		ctx.drawString(text, 0, 0)
		ctx.transform = oldTransform
		if (pic != null) {
			val sz = metrics.height
			ctx.drawImage(pic.toBufferedImage(), x + (metrics.stringWidth(text) - sz) / 2, y - sz, sz, sz, null)
		}
	})
}

fun spawnerTagsToTandem(tags: FGameplayTagContainer, tandems: List<FortTandemCharacterData>): FortTandemCharacterData? {
	// Athena.AI.SpawnLocation.Tandem.Bushranger -> AISpawnerData.Type.Tandem.Bushranger
	val tandemTag = tags.first().toString().substring("Athena.AI.SpawnLocation.Tandem.".length)
	val search = "AISpawnerData.Type.Tandem.$tandemTag"
	val tandem = tandems.firstOrNull { it.GameplayTag.toString().startsWith(search, true) }
		?: return null
	return tandem
}