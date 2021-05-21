package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.ProfileManager
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.format
import com.tb24.fn.util.getInt
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortCollectionBookData
import me.fungames.jfortniteparse.fort.objects.rows.FortCollectionBookPageCategoryTableRow
import me.fungames.jfortniteparse.fort.objects.rows.FortCollectionBookPageData
import me.fungames.jfortniteparse.fort.objects.rows.FortCollectionBookSectionData
import me.fungames.jfortniteparse.fort.objects.rows.FortCollectionBookSlotData
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import java.util.*
import java.util.concurrent.CompletableFuture

val CATEGORY_COMPARATOR = Comparator<CollectionBookCategory> { o1, o2 -> o1.backing.SortPriority - o2.backing.SortPriority }
val PAGE_COMPARATOR = Comparator<CollectionBookPage> { o1, o2 -> o1.backing.SortPriority - o2.backing.SortPriority }
val collectionBookData by lazy { loadObject<FortCollectionBookData>("/SaveTheWorld/CollectionBook/Data/CollectionBookData")!! }

class CollectionBookCommand : BrigadierCommand("collectionbook", "Shows your collection book.", arrayOf("cb")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			val context = obtainContext(source)
			val embed = source.createEmbed().setTitle("Collection Book")
			for (category in context.categories.values.sortedWith(CATEGORY_COMPARATOR)) {
				val ctgName = category.backing.Name.format()
				val ctgSlottables = category.calculateSlottables()
				embed.addField(if (ctgSlottables > 0) "%s \u2013 **%,d**".format(ctgName, ctgSlottables) else ctgName, category.pages.joinToString("\n") { item ->
					var slottables = 0
					var slotted = 0
					var total = 0
					for (section in item.sections) {
						for (slot in section.slots) {
							slottables += slot.slottables.size
							if (slot.profileItem != null) ++slotted
							++total
						}
					}
					var s = item.backing.Name.format().toString()
					if (slottables > 0) {
						s += " \u2013 **%,d**".format(slottables)
					}
					s + if (slotted < total) {
						" \u2013 %,d/%,d".format(slotted, total)
					} else {
						" ✅"
					}
				}, true)
			}
			source.complete(null, embed.build())
			Command.SINGLE_SUCCESS
		}
		.then(argument("page", greedyString())
			.executes { c ->
				val source = c.source
				val context = obtainContext(source)
				val page = context.pages.search(getString(c, "page")) { it.backing.Name.format()!! }
					?: throw SimpleCommandExceptionType(LiteralMessage("Can't find a page of that name. You can see all the page names in `${source.prefix}${c.commandName}`.")).create()
				val embed = source.createEmbed().setTitle("Collection Book / " + page.backing.Name.format())
				for (section in page.sections) {
					embed.addField(section.backing.Name.format(), section.slots.joinToString("\n") { it.profileItem?.render() ?: "Unslotted" }, true)
				}
				source.complete(null, embed.build())
				Command.SINGLE_SUCCESS
			}
		)

	private fun obtainContext(source: CommandSourceStack): CollectionBookContext {
		source.ensureSession()
		source.loading("Loading collection book")
		val profileManager = source.api.profileManager
		CompletableFuture.allOf(
			profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign"),
			profileManager.dispatchClientCommandRequest(QueryProfile(), "collection_book_people0"),
			profileManager.dispatchClientCommandRequest(QueryProfile(), "collection_book_schematics0")
		).await()
		val context = CollectionBookContext()
		context.populateFromProfiles(profileManager)
		return context
	}
}

class CollectionBookContext {
	val categories = hashMapOf<String, CollectionBookCategory>()
	val pages = hashSetOf<CollectionBookPage>()
	val slots = mutableListOf<CollectionBookSlot>()
	val slotItemsIndex = hashMapOf<String, CollectionBookSlot>()

	init {
		for ((pageId, pageData) in collectionBookData.PageData.value.rows.mapValues { it.value.mapToClass(FortCollectionBookPageData::class.java) }) {
			val categoryId = pageData.CategoryId
			val category = categories.getOrPut(categoryId.text) {
				val categoryData = collectionBookData.PageCategoryData.value.findRowMapped<FortCollectionBookPageCategoryTableRow>(categoryId)!!
				CollectionBookCategory(categoryId.text, categoryData)
			}
			val page = CollectionBookPage(pageId.text, pageData, this)
			category.pages.add(page)
			pages.add(page)
		}
	}

	fun populateFromProfiles(profileManager: ProfileManager) {
		val profileDataCampaign = profileManager.getProfileData("campaign")
		val profileDataCollectionBookPeople0 = profileManager.getProfileData("collection_book_people0")
		val profileDataCollectionBookSchematics0 = profileManager.getProfileData("collection_book_schematics0")
		for (slot in slots) {
			slot.profileItem = null
			slot.slottables.clear()
		}
		for (item in profileDataCollectionBookPeople0.items.values) {
			var slot = slotItemsIndex[item.primaryAssetName]
			if (item.attributes.has("personality")) {
				val personality = item.attributes["personality"]
				if (personality.isJsonPrimitive) {
					val workerSlot = slotItemsIndex[item.primaryAssetName + ':' + personality.asString]
					slot = workerSlot ?: slot
				}
			}
			if (slot != null) {
				slot.profileItem = item
				slot.profileId = profileDataCollectionBookPeople0.profileId
			}
		}
		for (item in profileDataCollectionBookSchematics0.items.values) {
			var slot = slotItemsIndex[item.primaryAssetName]
			if (item.attributes.has("personality")) {
				val workerSlot = slotItemsIndex[item.primaryAssetName + ':' + item.attributes["personality"].asString]
				slot = workerSlot ?: slot
			}
			if (slot != null) {
				slot.profileItem = item
				slot.profileId = profileDataCollectionBookSchematics0.profileId
			}
		}
		for (item in profileDataCampaign.items.values) {
			var slot = slotItemsIndex[item.primaryAssetName]
			if (item.attributes.has("personality")) {
				val workerSlot = slotItemsIndex[item.primaryAssetName + ':' + item.attributes["personality"].asString]
				slot = workerSlot ?: slot
			}
			if (slot != null && (slot.profileItem == null || item.attributes.getInt("level", 1) > slot.profileItem!!.attributes.getInt("level", 1))) {
				slot.slottables.add(item)
			}
		}
	}
}

class CollectionBookCategory(val id: String, val backing: FortCollectionBookPageCategoryTableRow) {
	val pages = TreeSet(PAGE_COMPARATOR)

	fun calculateSlottables() = pages.sumOf { it.calculateSlottables() }
}

class CollectionBookPage(val id: String, val backing: FortCollectionBookPageData, context: CollectionBookContext) {
	val sections = backing.SectionRowNames.map {
		val sectionData = collectionBookData.SectionData.value.findRowMapped<FortCollectionBookSectionData>(it)!!
		CollectionBookSection(it.text, sectionData, context)
	}

	fun calculateSlottables() = sections.sumOf { it.calculateSlottables() }
}

class CollectionBookSection(val id: String, val backing: FortCollectionBookSectionData, context: CollectionBookContext) {
	val slots = mutableListOf<CollectionBookSlot>()

	init {
		for (rowName in backing.SlotRowNames) {
			val slotData = collectionBookData.SlotData.value.findRowMapped<FortCollectionBookSlotData>(rowName)!!
			val slot = CollectionBookSlot(slotData)
			slots.add(slot)
			context.slots.addAll(slots)
			slotData.AllowedItems.forEachIndexed { i, it ->
				var key = it.toString().substringAfter('.').toLowerCase(Locale.ROOT)
				slotData.AllowedWorkerPersonalities.getOrNull(i)?.let {
					key += ":$it"
				}
				context.slotItemsIndex[key] = slot
			}
		}
	}

	fun calculateSlottables() = slots.sumOf { it.slottables.size }
}

class CollectionBookSlot(val backing: FortCollectionBookSlotData) {
	var profileItem: FortItemStack? = null
	lateinit var profileId: String
	var slottables = hashSetOf<FortItemStack>()
}