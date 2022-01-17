package com.tb24.discordbot.commands

import com.google.gson.JsonObject
import com.google.gson.internal.bind.util.ISO8601Utils
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.images.generateLockerImage
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.McpVariantReader
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.subgame.SetItemFavoriteStatusBatch
import com.tb24.fn.util.*
import me.fungames.jfortniteparse.fort.exports.AthenaCosmeticItemDefinition
import me.fungames.jfortniteparse.fort.exports.AthenaItemWrapDefinition
import me.fungames.jfortniteparse.fort.exports.variants.*
import me.fungames.jfortniteparse.util.scale
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.commands.OptionType
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
		"AthenaDance:AthenaDanceItemDefinition" to "Dances",
		"AthenaDance:AthenaEmojiItemDefinition" to "Emoticons",
		"AthenaDance:AthenaSprayItemDefinition" to "Sprays",
		"AthenaDance:AthenaToyItemDefinition" to "Toys",
		"AthenaItemWrap" to "Wraps",
		"AthenaMusicPack" to "Musics",
		"AthenaLoadingScreen" to "Loading Screens"
	)
	val icons = mapOf(
		"AthenaCharacter" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Ui_Outfit_256.T_Ui_Outfit_256",
		"AthenaBackpack" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Ui_BackBling_256.T_Ui_BackBling_256",
		"AthenaPickaxe" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Ui_Pick_256.T_Ui_Pick_256",
		"AthenaGlider" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Ui_Glider_256.T_Ui_Glider_256",
		"AthenaSkyDiveContrail" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Ui_Trail_256.T_Ui_Trail_256",
		"AthenaDance:AthenaDanceItemDefinition" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Ui_Dance_256.T_Ui_Dance_256",
		"AthenaDance:AthenaEmojiItemDefinition" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Icon_Emoticon_128.T_Icon_Emoticon_128",
		"AthenaDance:AthenaSprayItemDefinition" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Icon_Spray_128.T_Icon_Spray_128",
		"AthenaDance:AthenaToyItemDefinition" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Icon_Toy_128.T_Icon_Toy_128",
		"AthenaItemWrap" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Icon_Wrap_128.T_Icon_Wrap_128", // TODO properly colored icon
		"AthenaMusicPack" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Ui_Music_256.T_Ui_Music_256",
		"AthenaLoadingScreen" to "/Game/UI/Foundation/Textures/Icons/Locker/T_Ui_LoadingScreen_256.T_Ui_LoadingScreen_256"
	)

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(argument("type", word())
			.executes { type(it.source, parseCosmeticType(getString(it, "type"))) }
		)
		.then(literal("fortnitegg")
			.executes { fortniteGG(it.source) }
		)
		.then(literal("text")
			.executes { executeText(it.source) }
			.then(argument("type", string())
				.executes { executeText(it.source, parseCosmeticType(getString(it, "type"))) }
			)
		)

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("image", description)
			.option(OptionType.STRING, "type", "Type of the cosmetic to view.")
			.executes {
				val type = it.getOption("type")?.asString?.let(::parseCosmeticType)
				if (type != null) {
					type(it, type)
				} else {
					execute(it)
				}
			}
		)
		.then(subcommand("text", "Shows your BR locker in paginated text.")
			.option(OptionType.STRING, "type", "Type of the cosmetic to view.")
			.executes {
				val type = it.getOption("type")?.asString?.let(::parseCosmeticType)
				if (type != null) {
					executeText(it, type)
				} else {
					executeText(it)
				}
			}
		)
		.then(subcommand("fortnitegg", "Shows your BR locker in Fortnite.GG website.")
			.executes(::fortniteGG)
		)

	private fun execute(source: CommandSourceStack): Int {
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
			finalItems[categoryKey] = if (categoryKey.contains(':')) {
				val primaryAssetType = categoryKey.substringBefore(':')
				val className = categoryKey.substringAfter(':')
				itemsByType[primaryAssetType]!!.filter { it.defData?.exportType == className }
			} else {
				itemsByType[categoryKey]!!
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
		perform(source, names[choice], icons[choice], finalItems[choice]).await()
			?: throw SimpleCommandExceptionType(LiteralMessage("No ${names[choice]}.")).create()
		return Command.SINGLE_SUCCESS
	}

	private fun type(source: CommandSourceStack, filterType: String): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val items = if (filterType.contains(':')) {
			val primaryAssetType = filterType.substringBefore(':')
			val className = filterType.substringAfter(':')
			athena.items.values.filter { it.primaryAssetType == primaryAssetType && it.defData?.exportType == className }
		} else {
			athena.items.values.filter { it.primaryAssetType == filterType }
		}
		if (items.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("Nothing here")).create()
		}
		source.loading("Generating and uploading image")
		perform(source, names[filterType], icons[filterType], items).await()
			?: throw SimpleCommandExceptionType(LiteralMessage("No ${names[filterType]}.")).create()
		return Command.SINGLE_SUCCESS
	}

	private fun fortniteGG(source: CommandSourceStack): Int {
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
		return Command.SINGLE_SUCCESS
	}

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
		source.queryUsers_map(queryAccountIds)
		source.replyPaginated(entries, 12) { content, page, pageCount ->
			val entriesStart = page * 12 + 1
			val entriesEnd = entriesStart + content.size
			val embed = source.createEmbed()
				.setTitle(names[filterType])
				.setDescription("Showing %,d to %,d of %,d entries".format(entriesStart, entriesEnd - 1, entries.size))
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			content.forEach { it.addTo(embed, source) }
			MessageBuilder(embed)
		}
		return Command.SINGLE_SUCCESS
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
		"AthenaCharacter:cid_183_athena_commando_m_modernmilitaryred",
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
		"AthenaGlider:glider_id_211_wildcatblue",
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
		"AthenaPickaxe:pickaxe_id_398_wildcatfemale",
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
		.executes { execute(it.source) }
		.then(literal("favoriteall").executes { executeUpdateFavorite(it.source, true) })
		.then(literal("unfavoriteall").executes { executeUpdateFavorite(it.source, false) })

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("view", description).executes { execute(it) })
		.then(subcommand("favoriteall", "Favorites all of your exclusives.").executes { executeUpdateFavorite(it, true) })
		.then(subcommand("unfavoriteall", "Unfavorites all of your exclusives.").executes { executeUpdateFavorite(it, false) })

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val items = getExclusiveItems(source, athena, false)
		source.loading("Generating and uploading image")
		perform(source, "Exclusives", "/Game/Athena/UI/Frontend/Art/T_UI_BP_BattleStar_L.T_UI_BP_BattleStar_L", items).await()
			?: throw SimpleCommandExceptionType(LiteralMessage("No Exclusives.")).create()
		return Command.SINGLE_SUCCESS
	}

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

private fun perform(source: CommandSourceStack, name: String?, icon: String?, ids: Collection<FortItemStack>?) = CompletableFuture.supplyAsync {
	if (ids.isNullOrEmpty()) {
		return@supplyAsync null
	}
	val items = ids.sortedWith(SimpleAthenaLockerItemComparator().apply { bPrioritizeFavorites = false })
	val image = generateLockerImage(items, name, icon, source.api.currentLoggedIn, source.author)
	var png = image.toPngArray()
	var scale = 1f
	while (png.size > Message.MAX_FILE_SIZE && scale > 0.2f) {
		png = image.scale((image.width * scale).toInt(), (image.height * scale).toInt(), Image.SCALE_SMOOTH).toPngArray()
		//println("png size ${png.size} scale $scale")
		scale -= 0.2f
	}
	source.complete(MessageBuilder("**$name** (${Formatters.num.format(ids.size)})").build(), AttachmentUpload(png, "$name-${source.api.currentLoggedIn.id}.png"))
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

	fun addTo(embed: EmbedBuilder, source: CommandSourceStack) {
		val title = "%s %s".format(getEmoteByName(cosmetic.rarity.name.toLowerCase() + '2')?.asMention, if (displayName.isNullOrEmpty()) cosmetic.primaryAssetName.toLowerCase() else displayName)
		val descriptionParts = mutableListOf<String>()
		defData?.ItemVariants?.forEach { lazyVariant ->
			val variantContainer = VariantContainer(lazyVariant.value, backendVariants)
			val activeVariantDisplayName = variantContainer.activeVariantDisplayName ?: return@forEach
			descriptionParts.add("%s (%s)".format(variantContainer.channelName, activeVariantDisplayName))
		}
		giftFromAccountId?.let {
			descriptionParts.add("🎁 ${source.userCache[it]?.displayName ?: "Unknown"}")
		}
		embed.addField(title, descriptionParts.joinToString("\n"), true)
	}
}

class VariantContainer(val cosmeticVariant: FortCosmeticVariant, backendVariants: Array<McpVariantReader> = emptyArray()) {
	val backendVariant: McpVariantReader?

	init {
		val localBackendChannelName = cosmeticVariant.backendChannelName
		backendVariant = backendVariants.firstOrNull { it.channel == localBackendChannelName }
	}

	val channelName get() = cosmeticVariant.VariantChannelName.format()

	val activeVariantDisplayName get() = when (cosmeticVariant) {
		is FortCosmeticItemTexture -> cosmeticVariant.getActive(backendVariant).let { "%s (%s)".format(it.displayName, it.shortDescription) }
		is FortCosmeticVariantBackedByArray -> cosmeticVariant.getActive(backendVariant)?.VariantName?.format() ?: "**UNKNOWN SUBTYPE PLEASE REPORT**"
		is FortCosmeticFloatSliderVariant -> "%d/%d".format(cosmeticVariant.getActive(backendVariant).toInt(), cosmeticVariant.MaxParamValue.toInt())
		is FortCosmeticNumericalVariant -> Formatters.num.format(cosmeticVariant.getActive(backendVariant))
		is FortCosmeticProfileBannerVariant -> null // Always the currently equipped banner, cannot be displayed
		is FortCosmeticRichColorVariant -> "#%08X".format(cosmeticVariant.getActive(backendVariant).toFColor(true).toPackedARGB())
		else -> "**UNKNOWN TYPE PLEASE REPORT**"
	}

	operator fun component1() = cosmeticVariant
	operator fun component2() = backendVariant
}
