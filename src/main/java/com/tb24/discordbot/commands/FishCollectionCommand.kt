package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.BoolArgumentType.getBool
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.Rune
import com.tb24.discordbot.util.*
import com.tb24.fn.model.QueryMultipleUserStats
import com.tb24.fn.model.assetdata.GameDataBR
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.CollectableFishAttributes
import com.tb24.fn.model.mcpprofile.item.CollectableFishAttributes.EFortCollectedState
import com.tb24.fn.model.mcpprofile.item.CollectableFishAttributes.FortMcpCollectedItemProperties
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.uasset.AssetManager.INSTANCE
import me.fungames.jfortniteparse.fort.exports.*
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import java.util.concurrent.CompletableFuture

class FishCollectionCommand : BrigadierCommand("fishcollection", "Shows your fish collection.", arrayOf("fishing", "collections")) {
	private val defaultGameDataBR by lazy { INSTANCE.provider.loadObject<GameDataBR>("/Game/Balance/DefaultGameDataBR.DefaultGameDataBR") }
	private val itemDefToItemVariantMapping by lazy { defaultGameDataBR?.ItemDefToItemVariantDataMappingAsset?.load<FortItemDefToItemVariantDataMapping>()?.ItemDefToItemVariantDataMappings }
	private val fishTagToItemVariantDataMapping by lazy { itemDefToItemVariantMapping?.map { it.ItemVariantData.load<FortItemVariantData>()!! }?.flatMap { it.Variants }?.associateBy { it.CollectionTag.TagName.text } }
	private val collectionsData by lazy { INSTANCE.provider.loadObject<FortCollectionsDataTable>("/Game/Athena/Collections/CollectionsData.CollectionsData")?.Collections }

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::hasAssetsLoaded)
		.executes(::execute)
		.then(argument("all", bool())
			.executes { execute(it, getBool(it, "all")) }
		)

	private fun execute(context: CommandContext<CommandSourceStack>, all: Boolean = false): Int {
		val source = context.source
		source.ensureSession()
		source.loading("Getting fishing data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "collections").await()
		val collected = source.api.profileManager.getProfileData("collections").items.values.flatMap { it.getAttributes(CollectableFishAttributes::class.java).collected.toList() }
		if (!all && collected.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You've never caught any fish yet. Use `${source.prefix}${context.nodes.first().node.name} true` to show all entries.")).create()
		}
		val friends = source.api.friendsService.queryFriends(source.api.currentLoggedIn.id, true).exec().body()!!
		val stats = friends.map { it.accountId }
			.chunked(50)//55)
			.map { source.api.statsproxyService.queryMultipleUserStats("collection_fish", QueryMultipleUserStats().apply { owners = it.toTypedArray() }).future() }
			.apply { CompletableFuture.allOf(*toTypedArray()).await() }
			.flatMap { it.get().body()!!.toList() }
		val data = collectionsData
			?.firstOrNull { it.CollectionType == "CollectableFish" }?.Collection?.load<FortCollectionDataFishing>()?.Entries
			?.mapIndexed { i, it -> it.load<FortCollectionDataEntryFish>()!!.run { Entry(this, EntryTag.TagName.run { collected.firstOrNull { it.variantTag == text } }, i) } }
			?: throw SimpleCommandExceptionType(LiteralMessage("Data not found.")).create()
		source.message.replyPaginated(if (all) data else data.filter { it.collected != null && it.collected.seenState != EFortCollectedState.New }, 1, source.loadingMsg) { content, page, pageCount ->
			val entry = content.first()
			val def = entry.def
			val collected = entry.collected
			val idx = entry.idx
			MessageBuilder(EmbedBuilder()
				.setAuthor('#' + Formatters.num.format(idx + 1))
				.setTitle(def.EntryName.format())
				.setDescription(def.EntryDescription.format())
				.setThumbnail(Utils.benBotExportAsset(def.LargeIcon.assetPathName.text))
				.addField("Hint", def.AdditionalEntryDescription.format()!!.replace("<text color=\"fff\" fontface=\"black\" case=\"upper\">", "**").replace("</>", "**"), false)
				.addField("Size", def.Size.getValue0().toString(), false)
				.apply {
					fishTagToItemVariantDataMapping?.get(def.EntryTag.TagName.text)?.apply {
						POITags.takeIf { it.gameplayTags.isNotEmpty() }?.apply { addFieldSeparate("POI tags", toList(), 0, true) }
						TODTags.takeIf { it.gameplayTags.isNotEmpty() }?.apply { addFieldSeparate("Time of day tags", toList(), 0, true) }
						// RequiredTags.takeIf { it.gameplayTags.isNotEmpty() }?.apply { addFieldSeparate("Required tags", toList(), 0, true) }
					}
					collected?.apply {
						addField("State", seenState.name, false)
						if (seenState != EFortCollectedState.New) {
							addField("Count", '\u00d7' + Formatters.num.format(count), true)
								.addField("Personal best", "\u2605".repeat(properties.weight) + ' ' + "%.2f cm".format(properties.length), true)
						}
					}

					// --- stats ---

					// Fish.EffectiveFlopper.Purple
					val split = def.EntryTag.TagName.text.split('.')
					// br_{type}         _{category}      _{variant}_{metric}_s{season}
					// br_collection_fish_effectiveflopper_purple   _length  _s14
					val statKey = "br_${"collection_fish"}_${split[1]}_${split[2]}_${"length"}_s${15 /* TODO WHY HARDCODE */}".toLowerCase()
					val map = mutableMapOf<String/*account id*/, Float/*length in cm*/>()
					if (collected != null) {
						map[source.api.currentLoggedIn.id] = collected.properties.length
					}
					map.putAll(stats.associate { it.accountId to (it.stats[statKey] ?: 0) / 1000f })
					addField("Scores", map.entries
						.filter { it.value > 0f }
						.sortedByDescending { it.value }
						.mapIndexed { i, e ->
							val dn: String
							val bold: String
							if (e.key == source.api.currentLoggedIn.id) {
								dn = source.api.currentLoggedIn.displayName
								bold = "**"
							} else {
								dn = friends.firstOrNull { it.accountId == e.key }?.displayName ?: e.key
								bold = ""
							}
							"$bold#${i + 1} ${dn.replace("_", "\\_").replace("*", "\\*")} ${"%.2f".format(e.value)} cm$bold"
						}
						.joinToString("\n", limit = 10, truncated = ""), false)
				}
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				.setColor(def.hashCode())
			).build()
		}
		return Command.SINGLE_SUCCESS
	}

	class Entry(val def: FortCollectionDataEntryFish, val collected: FortMcpCollectedItemProperties?, val idx: Int)
}