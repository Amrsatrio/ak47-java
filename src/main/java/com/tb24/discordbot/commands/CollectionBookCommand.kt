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
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.format
import com.tb24.fn.util.getString
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortCollectionBookData
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition
import me.fungames.jfortniteparse.fort.objects.rows.*
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.EmbedBuilder
import java.util.*
import java.util.concurrent.CompletableFuture

@JvmField val CATEGORY_COMPARATOR = Comparator<CollectionBookCategory> { o1, o2 -> o1.backing.SortPriority - o2.backing.SortPriority }
@JvmField val PAGE_COMPARATOR = Comparator<CollectionBookPage> { o1, o2 -> o1.backing.SortPriority - o2.backing.SortPriority }
val collectionBookData by lazy { loadObject<FortCollectionBookData>("/SaveTheWorld/CollectionBook/Data/CollectionBookData")!! }
val pageDataMapped by lazy { collectionBookData.PageData.value.rows.mapValues { it.value.mapToClass(FortCollectionBookPageData::class.java) } }
val slotXpWeightDataMapped by lazy { collectionBookData.XPWeightData.value.rows.mapValues { it.value.mapToClass(FortCollectionBookSlotXPWeightData::class.java) } }
val xpDataMapped by lazy { collectionBookData.BookXPData.value.rows.mapValues { it.value.mapToClass(FortCollectionBookXPData::class.java) } }

class CollectionBookCommand : BrigadierCommand("collectionbook", "Shows your collection book.", arrayOf("cb")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			val context = obtainContext(source)
			val embed = source.createEmbed().setTitle("Collection Book").appendXpField(context)
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
				val nothing = getEmoteByName("nothing")?.asMention ?: ""
				for (section in page.sections) {
					val sectionTitle = section.backing.Name.format()
					embed.addField(sectionTitle, section.slots.joinToString("\n") { slot ->
						val profileItem = slot.profileItem
						val item = if (profileItem != null) {
							profileItem
						} else {
							val dummy = FortItemStack(slot.backing.AllowedItems[0].load<FortItemDefinition>(), 1)
							dummy.attributes.addProperty("level", 1)
							slot.backing.AllowedWorkerPersonalities.firstOrNull()?.let {
								dummy.attributes.addProperty("personality", it.toString())
							}
							dummy
						}
						//val rarityIcon = getEmoteByName(item.rarity.name.toLowerCase() + '2')?.asMention ?: nothing
						val dn = item.displayName
						"`%d` %s %s%s".format(slot.index + 1, if (profileItem != null) "✅" else nothing, item.rarity.rarityName.format(), if (dn != sectionTitle) ' ' + item.render() else "")
					}, true)
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
		return CollectionBookContext().apply { populateFromProfiles(profileManager) }
	}

	private fun EmbedBuilder.appendXpField(context: CollectionBookContext): EmbedBuilder {
		val xp = context.calculateXp()
		var currentLvl: Map.Entry<FName, FortCollectionBookXPData>? = null
		var nextLvl: Map.Entry<FName, FortCollectionBookXPData>? = null
		var nextMajorLvl: Map.Entry<FName, FortCollectionBookXPData>? = null
		for (it in xpDataMapped) {
			if (currentLvl == null) {
				if (xp >= it.value.TotalXpToGetToThisLevel && xp < (it.value.TotalXpToGetToThisLevel + it.value.XpToNextLevel) || it.value.XpToNextLevel == 0) {
					currentLvl = it
				}
			} else if (nextLvl == null) {
				nextLvl = it
			} else if (nextMajorLvl == null && it.value.bIsMajorReward) {
				nextMajorLvl = it
			}
			if (currentLvl != null && nextLvl != null && nextMajorLvl != null) {
				break
			}
		}
		addField("Level %,d".format(currentLvl!!.key.toString().toInt() + 1), "`%s`\n%,d/%,d".format(
			Utils.progress(xp - currentLvl.value.TotalXpToGetToThisLevel, currentLvl.value.XpToNextLevel, 32),
			xp, currentLvl.value.TotalXpToGetToThisLevel + currentLvl.value.XpToNextLevel
		), false)
		return this
	}
}

class CollectionBookContext {
	val categories = hashMapOf<String, CollectionBookCategory>()
	val pages = hashSetOf<CollectionBookPage>()
	val slots = mutableListOf<CollectionBookSlot>()
	val slotItemsIndex = hashMapOf<String, CollectionBookSlot>()

	init {
		for ((pageId, pageData) in pageDataMapped) {
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
		populateFromCollectionBookProfile(profileDataCollectionBookPeople0)
		populateFromCollectionBookProfile(profileDataCollectionBookSchematics0)
		for (item in profileDataCampaign.items.values) {
			if (item.isPermanent) continue
			if (item.isFavorite) continue
			if (!item.attributes.getString("squad_id").isNullOrEmpty()) {
				continue
			}
			val slot = findSlot(item)
			if (slot != null && (slot.profileItem == null || item.level > slot.profileItem!!.level)) {
				slot.slottables.add(item)
			}
		}
	}

	private inline fun populateFromCollectionBookProfile(profile: McpProfile) {
		for (item in profile.items.values) {
			findSlot(item)?.apply {
				profileItem = item
				profileId = profile.profileId
			}
		}
	}

	private fun findSlot(item: FortItemStack): CollectionBookSlot? {
		var slot = slotItemsIndex[item.primaryAssetName]
		val personality = item.attributes.getString("personality")
		if (personality != null) {
			val workerSlot = slotItemsIndex[item.primaryAssetName + ':' + personality]
			slot = workerSlot ?: slot
		}
		return slot
	}

	fun calculateXp() = slots.sumOf { it.calculateXp() }
}

class CollectionBookCategory(val id: String, val backing: FortCollectionBookPageCategoryTableRow) {
	val pages = TreeSet(PAGE_COMPARATOR)

	inline fun calculateSlottables() = pages.sumOf { it.calculateSlottables() }
}

class CollectionBookPage(val id: String, val backing: FortCollectionBookPageData, context: CollectionBookContext) {
	val sections = backing.SectionRowNames.map {
		val sectionData = collectionBookData.SectionData.value.findRowMapped<FortCollectionBookSectionData>(it)!!
		CollectionBookSection(it.text, sectionData, context)
	}

	inline fun calculateSlottables() = sections.sumOf { it.calculateSlottables() }
}

class CollectionBookSection(val id: String, val backing: FortCollectionBookSectionData, context: CollectionBookContext) {
	val slots = mutableListOf<CollectionBookSlot>()

	init {
		for (rowName in backing.SlotRowNames) {
			val slotData = collectionBookData.SlotData.value.findRowMapped<FortCollectionBookSlotData>(rowName)!!
			val slot = CollectionBookSlot(slotData)
			slot.index = context.slots.size + slots.size
			slots.add(slot)
			slotData.AllowedItems.forEachIndexed { i, it ->
				var key = it.toString().substringAfter('.').toLowerCase(Locale.ROOT)
				slotData.AllowedWorkerPersonalities.getOrNull(i)?.let {
					key += ":$it"
				}
				context.slotItemsIndex[key] = slot
			}
		}
		context.slots.addAll(slots)
	}

	inline fun calculateSlottables() = slots.sumOf { it.slottables.size }
}

class CollectionBookSlot(val backing: FortCollectionBookSlotData) {
	var profileItem: FortItemStack? = null
	lateinit var profileId: String
	var slottables = hashSetOf<FortItemStack>()
	var index = -1

	fun calculateXp(): Int {
		val item = profileItem ?: return 0
		slotXpWeightDataMapped[backing.SlotXpWeightName]!!.apply {
			val constant = ConstantWeight
			val rarity = collectionBookData.SlotRarityFactorData.value.FloatCurve!!.eval(item.rarity.ordinal.toFloat()) * RarityWeight
			val premiumTier = 0 * PremiumTierWeight
			val itemLevel = item.level * ItemLevelWeight
			val itemRating = item.powerLevel * ItemRatingWeight
			return (constant + rarity + premiumTier + itemLevel + itemRating).toInt()
		}
	}
}