package com.tb24.discordbot.commands

import com.google.gson.JsonObject
import com.google.gson.internal.bind.util.ISO8601Utils
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.images.GridSlot
import com.tb24.discordbot.images.createAttachmentOfIcons
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.McpVariantReader
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.subgame.SetItemFavoriteStatusBatch
import com.tb24.fn.util.*
import me.fungames.jfortniteparse.fort.exports.AthenaCosmeticItemDefinition
import me.fungames.jfortniteparse.fort.exports.AthenaItemWrapDefinition
import me.fungames.jfortniteparse.fort.exports.variants.*
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.util.scale
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.Button
import net.dv8tion.jda.api.interactions.components.ButtonStyle
import okhttp3.Request
import java.awt.Image
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class LockerCommand : BrigadierCommand("locker", "Shows your BR locker in form of an image.") {
	// PrimaryAssetType[:ClassName]
	val names = mapOf(
		"AthenaCharacter" to "Outfits",
		"AthenaBackpack" to "Back Blings",
		"AthenaPickaxe" to "Harvesting Tools",
		"AthenaGlider" to "Gliders",
		"AthenaSkyDiveContrail" to "Contrails",
		"AthenaDance:AthenaDanceItemDefinition" to "Emotes",
		"AthenaDance:AthenaSprayItemDefinition" to "Sprays",
		"AthenaDance:AthenaEmojiItemDefinition" to "Emoticons",
		"AthenaDance:AthenaToyItemDefinition" to "Toys",
		"AthenaItemWrap" to "Wraps",
		"AthenaMusicPack" to "Musics",
		"AthenaLoadingScreen" to "Loading Screens"
	)

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting cosmetics")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val typesToQuery = names.keys.map { it.substringBefore(':') }.toSet()
			val itemsByType = typesToQuery.associateWith { mutableListOf<FortItemStack>() }
			for (item in athena.items.values) {
				itemsByType[item.primaryAssetType]?.add(item)
			}
			val finalItems = mutableMapOf<String, List<FortItemStack>>()
			for (categoryKey in names.keys) {
				if (categoryKey.contains(':')) {
					val primaryAssetType = categoryKey.substringBefore(':')
					val className = categoryKey.substringAfter(':')
					finalItems[categoryKey] = itemsByType[primaryAssetType]!!.filter { it.defData?.exportType == className }
				} else {
					finalItems[categoryKey] = itemsByType[categoryKey]!!
				}
			}
			val buttons = mutableListOf<Button>()
			for ((categoryKey, items) in finalItems) {
				if (items.isEmpty()) continue
				buttons.add(Button.of(ButtonStyle.SECONDARY, categoryKey, "%s (%,d)".format(names[categoryKey]!!, items.size)))
			}
			val message = source.complete("**Pick a category**", null, *buttons.chunked(5, ActionRow::of).toTypedArray())
			val choice = message.awaitOneInteraction(source.author).componentId
			source.loading("Generating and uploading image")
			perform(source, names[choice], finalItems[choice]).await()
			source.loadingMsg!!.delete().queue()
			Command.SINGLE_SUCCESS
		}
		.then(argument("type", word())
			.executes { c ->
				val source = c.source
				val filterType = parseType(getString(c, "type"))
				source.ensureSession()
				source.loading("Getting cosmetics")
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
				val athena = source.api.profileManager.getProfileData("athena")
				val items = athena.items.values.filter { it.primaryAssetType == filterType }
				if (items.isEmpty()) {
					throw SimpleCommandExceptionType(LiteralMessage("Nothing here")).create()
				}
				source.loading("Generating and uploading image")
				perform(source, names[filterType], items).await()
				source.loadingMsg!!.delete().queue()
				Command.SINGLE_SUCCESS
			}
		)
		.then(literal("fortnitegg")
			.executes { c ->
				val source = c.source
				source.ensureSession()
				source.loading("Getting cosmetics")
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
				val athena = source.api.profileManager.getProfileData("athena")
				val fnggItems = source.api.okHttpClient.newCall(Request.Builder().url("https://fortnite.gg/api/items.json").build()).exec().to<JsonObject>()
				val types = arrayOf(
					"AthenaCharacter",
					"AthenaBackpack",
					"AthenaPickaxe",
					"AthenaGlider",
					"AthenaSkyDiveContrail",
					"AthenaDance",
					"AthenaItemWrap",
					"AthenaMusicPack",
					"AthenaLoadingScreen"
				)
				val ints = mutableListOf<Int>()
				for (item in athena.items.values) {
					if (item.primaryAssetType !in types) {
						continue
					}
					fnggItems.entrySet().firstOrNull { it.key.equals(item.primaryAssetName, true) }?.let { ints.add(it.value.asInt) }
				}
				ints.sort()
				val diff = ints.mapIndexed { i, it -> if (i > 0) it - ints[i - 1] else it }
				val os = ByteArrayOutputStream()
				DeflaterOutputStream(os, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use {
					it.write((ISO8601Utils.format(athena.created) + ',' + diff.joinToString(",")).toByteArray())
				}
				val encodedCosmetics = Base64.getUrlEncoder().encodeToString(os.toByteArray())
				var url = "https://fortnite.gg/my-locker?items=$encodedCosmetics"
				url = url.shortenUrl(source)
				source.complete(null, source.createEmbed()
					.setTitle("View your locker on Fortnite.GG", url)
					.build())
				Command.SINGLE_SUCCESS
			}
		)
		.then(literal("text")
			.executes { executeText(it.source) }
			.then(argument("type", string())
				.executes { executeText(it.source, parseType(getString(it, "type"))) }
			)
		)

	private fun executeText(source: CommandSourceStack, filterType: String = "AthenaCharacter"): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val queryAccountIds = mutableSetOf<String>()
		val entries = athena.items.values
			.filter { it.primaryAssetType == filterType }
			.sortedWith(SimpleAthenaLockerItemComparator().apply { bPrioritizeFavorites = false })
			.map { LockerEntry(it, queryAccountIds) }
		if (entries.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No")).create()
		}
		val users = source.queryUsers(queryAccountIds)
		source.message.replyPaginated(entries, 12, source.loadingMsg) { content, page, pageCount ->
			val entriesStart = page * 12 + 1
			val entriesEnd = entriesStart + content.size
			val embed = source.createEmbed()
				.setTitle(names[filterType])
				.setDescription("Showing %,d to %,d of %,d entries".format(entriesStart, entriesEnd - 1, entries.size))
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			content.forEach { it.addTo(embed, users) }
			MessageBuilder(embed).build()
		}
		return Command.SINGLE_SUCCESS
	}

	private fun parseType(type: String): String {
		val lowerType = type.toLowerCase(Locale.ROOT)
		val filterType = when (if (lowerType.endsWith('s')) lowerType.substring(0, lowerType.length - 1) else lowerType) {
			"character", "outfit", "skin" -> "AthenaCharacter"
			"backpack", "backbling" -> "AthenaBackpack"
			"pickaxe", "harvestingtool" -> "AthenaPickaxe"
			"glider" -> "AthenaGlider"
			"skydivecontrail", "contrail" -> "AthenaSkyDiveContrail"
			"dance", "emote" -> "AthenaDance:AthenaDanceItemDefinition"
			"spray" -> "AthenaDance:AthenaSprayItemDefinition"
			"emoticon" -> "AthenaDance:AthenaEmojiItemDefinition"
			"toy" -> "AthenaDance:AthenaToyItemDefinition"
			"itemwrap", "wrap" -> "AthenaItemWrap"
			"musicpack", "music" -> "AthenaMusicPack"
			"loadingscreen" -> "AthenaLoadingScreen"
			else -> throw SimpleCommandExceptionType(LiteralMessage("Unknown cosmetic type $type. Valid values are: (case insensitive)```\nOutfit, BackBling, HarvestingTool, Glider, Contrail, Emote, Spray, Emoticon, Toy, Wrap, Music, LoadingScreen\n```")).create()
		}
		return filterType
	}
}

class ExclusivesCommand : BrigadierCommand("exclusives", "Shows your exclusive cosmetics in an image.") {
	private val exclusivesOverride = listOf(
		"AthenaBackpack:bid_055_psburnout",
		"AthenaBackpack:bid_138_celestial",
		"AthenaBackpack:bid_169_mathmale",
		"AthenaBackpack:bid_224_techopsblue",
		"AthenaBackpack:bid_234_speedymidnight",
		"AthenaBackpack:bid_249_streetopsstealth",
		"AthenaBackpack:bid_288_cyberscavengerfemaleblue",
		"AthenaBackpack:bid_346_blackwidowrogue",
		"AthenaBackpack:bid_448_techopsbluefemale",
		"AthenaBackpack:bid_482_longshorts",
		"AthenaBackpack:bid_521_wildcat",
		"AthenaBackpack:bid_604_skullbritecube",
		"AthenaCharacter:cid_017_athena_commando_m",
		"AthenaCharacter:cid_028_athena_commando_f",
		"AthenaCharacter:cid_035_athena_commando_m_medieval",
		"AthenaCharacter:cid_039_athena_commando_f_disco",
		"AthenaCharacter:cid_052_athena_commando_f_psblue",
		"AthenaCharacter:cid_085_athena_commando_m_twitch",
		"AthenaCharacter:cid_089_athena_commando_m_retrogrey",
		"AthenaCharacter:cid_095_athena_commando_m_founder",
		"AthenaCharacter:cid_096_athena_commando_f_founder",
		"AthenaCharacter:cid_113_athena_commando_m_blueace",
		"AthenaCharacter:cid_114_athena_commando_f_tacticalwoodland",
		"AthenaCharacter:cid_138_athena_commando_m_psburnout",
		"AthenaCharacter:cid_174_athena_commando_f_carbidewhite",
		"AthenaCharacter:cid_175_athena_commando_m_celestial",
		"AthenaCharacter:cid_183_athena_commando_m_modernmilitaryred",,
		"AthenaCharacter:cid_296_athena_commando_m_math",
		"AthenaCharacter:cid_313_athena_commando_m_kpopfashion",
		"AthenaCharacter:cid_342_athena_commando_m_streetracermetallic",
		"AthenaCharacter:cid_371_athena_commando_m_speedymidnight",
		"AthenaCharacter:cid_386_athena_commando_m_streetopsstealth",
		"AthenaCharacter:cid_434_athena_commando_f_stealthhonor",
		"AthenaCharacter:cid_441_athena_commando_f_cyberscavengerblue",
		"AthenaCharacter:cid_478_athena_commando_f_worldcup",
		"AthenaCharacter:cid_479_athena_commando_f_davinci",
		"AthenaCharacter:cid_516_athena_commando_m_blackwidowrogue",
		"AthenaCharacter:cid_619_athena_commando_f_techllama",
		"AthenaCharacter:cid_657_athena_commando_f_techopsblue",
		"AthenaCharacter:cid_711_athena_commando_m_longshorts",
		"AthenaCharacter:cid_757_athena_commando_f_wildcat",
		"AthenaCharacter:cid_783_athena_commando_m_aquajacket",
		"AthenaCharacter:cid_850_athena_commando_f_skullbritecube",
		"AthenaCharacter:cid_926_athena_commando_f_streetfashiondiamond",
		"AthenaDance:eid_davinci",
		"AthenaDance:eid_floss",
		"AthenaDance:eid_hiphop01",
		"AthenaDance:eid_kpopdance03",
		"AthenaDance:eid_playereleven",
		"AthenaDance:spid_065_discoballer",
		"AthenaDance:spid_066_llamalaxy",
		"AthenaGlider:founderglider",
		"AthenaGlider:founderumbrella",
		"AthenaGlider:glider_id_001",
		"AthenaGlider:glider_id_013_psblue",
		"AthenaGlider:glider_id_056_carbidewhite",
		"AthenaGlider:glider_id_067_psburnout",
		"AthenaGlider:glider_id_090_celestial",
		"AthenaGlider:glider_id_131_speedymidnight",
		"AthenaGlider:glider_id_137_streetopsstealth",
		"AthenaGlider:glider_id_137_streetopsstealth",
		"AthenaGlider:glider_id_217_longshortsmale",
		"AthenaGlider:glider_warthog",
		"AthenaItemWrap:wrap_121_techopsblue",
		"AthenaPickaxe:pickaxe_id_013_teslacoil",
		"AthenaPickaxe:pickaxe_id_039_tacticalblack",
		"AthenaPickaxe:pickaxe_id_077_carbidewhite",
		"AthenaPickaxe:pickaxe_id_088_psburnout",
		"AthenaPickaxe:pickaxe_id_116_celestial",
		"AthenaPickaxe:pickaxe_id_153_roseleader",
		"AthenaPickaxe:pickaxe_id_178_speedymidnight",
		"AthenaPickaxe:pickaxe_id_189_streetopsstealth",
		"AthenaPickaxe:pickaxe_id_189_streetopsstealth",
		"AthenaPickaxe:pickaxe_id_237_warpaint",
		"AthenaPickaxe:pickaxe_id_256_techopsblue",
		"AthenaPickaxe:pickaxe_id_294_candycane",
		"AthenaPickaxe:cid_850_athena_commando_f_skullbritecube",
		"AthenaPickaxe:pickaxe_id_464_longshortsmale",
		"AthenaPickaxe:pickaxe_id_stw001_tier_1",
		"AthenaPickaxe:pickaxe_id_stw006_tier_7",
		"AthenaPickaxe:pickaxe_id_stw007_basic",
		"AthenaPickaxe:pickaxe_lockjaw",
		"AthenaSkyDiveContrail:trails_id_019_psburnout",
		"AthenaSkyDiveContrail:trails_id_059_sony2",
		"AthenaSkyDiveContrail:trails_id_091_longshorts",
		"CosmeticVariantToken:vtid_052_skull_trooper_redflames",
		"CosmeticVariantToken:vtid_053_ghost_portal__redflames",
		"CosmeticVariantToken:vtid_259_m_teriyakifish_styled",
		"CosmeticVariantToken:vtid_389_halloween_stylec",
		"CosmeticVariantToken:vtid_828_m_jupiter_styleb",
		"CosmeticVariantToken:vtid_837_m_historian_styleb",
		"CosmeticVariantToken:vtid_976_alchemy_styleb",
		"CosmeticVariantToken:vtid_a_410_rustyboltmale_styleb",
	)

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting cosmetics")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val items = getExclusiveItems(source, athena, false)
			source.loading("Generating and uploading image")
			perform(source, "Exclusives", items).await()
			source.loadingMsg!!.delete().queue()
			Command.SINGLE_SUCCESS
		}
		.then(literal("favoriteall").executes { executeUpdateFavorite(it.source, true) })
		.then(literal("unfavoriteall").executes { executeUpdateFavorite(it.source, false) })

	private fun executeUpdateFavorite(source: CommandSourceStack, favorite: Boolean): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val items = getExclusiveItems(source, athena, true)
		val toFavorite = items.filter { it.attributes.getBoolean("favorite") != favorite }
		if (toFavorite.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage(if (favorite) "Nothing to favorite." else "Nothing to unfavorite.")).create()
		}
		val embed = source.createEmbed().setColor(COLOR_WARNING)
			.setDescription((if (favorite) "Favorite **%,d** of **%,d** exclusive items?" else "Unfavorite **%,d** of **%,d** exclusive items?").format(toFavorite.size, items.size))
		val confirmationMsg = source.complete(null, embed.build(), confirmationButtons())
		if (!confirmationMsg.awaitConfirmation(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
		source.api.profileManager.dispatchClientCommandRequest(SetItemFavoriteStatusBatch().apply {
			itemIds = toFavorite.map { it.itemId }.toTypedArray()
			itemFavStatus = BooleanArray(toFavorite.size) { favorite }.toTypedArray()
		}, "athena").await()
		confirmationMsg.editMessage(embed.setColor(COLOR_SUCCESS)
			.setDescription("✅ " + (if (favorite) "Favorited %,d exclusives!" else "Unfavorited %,d exclusives!").format(toFavorite.size))
			.build()).complete()
		return Command.SINGLE_SUCCESS
	}

	private fun getExclusiveItems(source: CommandSourceStack, athena: McpProfile, onlyCosmetics: Boolean): List<FortItemStack> {
		//val exclusivesResponse = runCatching { source.client.okHttpClient.newCall(Request.Builder().url("https://fort-api.com/exclusives/list").build()).exec() }
		val exclusiveTemplateIds = /*if (exclusivesResponse.isSuccess) {
			val out = mutableListOf<String>()
			exclusivesResponse.getOrThrow().body()!!.charStream().use { it.forEachLine(out::add) }
			out
		} else*/ exclusivesOverride
		return athena.items.values.filter { item -> exclusiveTemplateIds.any { it.equals(item.templateId, true) } && (!onlyCosmetics || item.primaryAssetType != "CosmeticVariantToken") }
	}
}

private fun perform(source: CommandSourceStack, name: String?, ids: Collection<FortItemStack>?) = CompletableFuture.supplyAsync {
	if (ids.isNullOrEmpty()) {
		return@supplyAsync null
	}
	val slots = mutableListOf<GridSlot>()
	for (item in ids.sortedWith(SimpleAthenaLockerItemComparator().apply { bPrioritizeFavorites = false })) {
		val itemData = item.defData ?: return@supplyAsync null
		slots.add(GridSlot(
			image = item.getPreviewImagePath()?.load<UTexture2D>()?.toBufferedImage(),
			name = item.displayName,
			rarity = itemData.Rarity
		))
	}
	val image = createAttachmentOfIcons(slots, "locker")
	var png = image.toPngArray()
	var scale = 1f
	while (png.size > Message.MAX_FILE_SIZE && scale > 0.2f) {
		png = image.scale((image.width * scale).toInt(), (image.height * scale).toInt(), Image.SCALE_SMOOTH).toPngArray()
		//println("png size ${png.size} scale $scale")
		scale -= 0.2f
	}
	source.channel.sendMessage("**$name** (${Formatters.num.format(ids.size)})")
		.addFile(png, "$name-${source.api.currentLoggedIn.id}.png").complete()
}

class LockerEntry(val cosmetic: FortItemStack, queryAccountIds: MutableCollection<String>) {
	private val defData = cosmetic.defData as? AthenaCosmeticItemDefinition
	var displayName = defData?.DisplayName.format()
	var description = defData?.Description.format()
	var shortDescription = defData?.ShortDescription.format()
	private val giftFromAccountId = cosmetic.attributes?.getString("giftFromAccountId")
	private val backendVariants = EpicApi.GSON.fromJson(cosmetic.attributes.getAsJsonArray("variants"), Array<McpVariantReader>::class.java)

	init {
		giftFromAccountId?.let { queryAccountIds.add(it) }
		if (shortDescription == null) {
			if (defData is AthenaItemWrapDefinition) {
				shortDescription = "Wrap"
			}
		}
	}

	fun addTo(embed: EmbedBuilder, users: List<GameProfile>) {
		val title = "%s %s".format(getEmoteByName(cosmetic.rarity.name.toLowerCase() + '2')?.asMention, if (displayName.isNullOrEmpty()) cosmetic.primaryAssetName.toLowerCase() else displayName)
		val descriptionParts = mutableListOf<String>()
		val itemVariants = defData?.ItemVariants
		if (itemVariants != null) {
			for (lazyVariant in itemVariants) {
				val cosmeticVariant = lazyVariant.value
				val localBackendChannelName = cosmeticVariant.backendChannelName
				val backendVariant = backendVariants.firstOrNull { it.channel == localBackendChannelName }
				val activeVariantDisplayName = when (cosmeticVariant) {
					is FortCosmeticVariantBackedByArray -> cosmeticVariant.getActive(backendVariant)?.VariantName?.format() ?: "**UNKNOWN SUBTYPE PLEASE REPORT**"
					is FortCosmeticFloatSliderVariant -> "%d/%d".format(cosmeticVariant.getActive(backendVariant).toInt(), cosmeticVariant.MaxParamValue.toInt())
					is FortCosmeticNumericalVariant -> Formatters.num.format(cosmeticVariant.getActive(backendVariant))
					is FortCosmeticProfileBannerVariant -> continue // Always the currently equipped banner
					is FortCosmeticRichColorVariant -> "#%08X".format(cosmeticVariant.getActive(backendVariant).toFColor(true).toPackedARGB())
					else -> "**UNKNOWN TYPE PLEASE REPORT**"
				}
				descriptionParts.add("%s (%s)".format(cosmeticVariant.VariantChannelName.format(), activeVariantDisplayName))
			}
		}
		if (giftFromAccountId != null) {
			descriptionParts.add("🎁 ${users.firstOrNull { it.id == giftFromAccountId }?.displayName ?: "Unknown"}")
		}
		embed.addField(title, descriptionParts.joinToString("\n"), true)
	}
}
