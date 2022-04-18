package com.tb24.discordbot.commands

import com.google.gson.JsonObject
import com.google.gson.internal.bind.util.ISO8601Utils
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.Rune
import com.tb24.discordbot.images.EXCLUSIVES_DISTINGUISH
import com.tb24.discordbot.images.EXCLUSIVES_INFO
import com.tb24.discordbot.images.GenerateLockerImageParams
import com.tb24.discordbot.images.generateLockerImage
import com.tb24.discordbot.item.*
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.McpVariantReader
import com.tb24.fn.model.assetdata.CustomDynamicColorSwatch
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
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import okhttp3.Request
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

class LockerCommand : BrigadierCommand("locker", "Shows your BR locker in form of an image.") {
	companion object {
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

		fun getLockerItems(athena: McpProfile, filterType: String): Collection<FortItemStack> {
			val items = if (filterType.contains(':')) {
				val primaryAssetType = filterType.substringBefore(':')
				val className = filterType.substringAfter(':')
				athena.items.values.filter { it.primaryAssetType == primaryAssetType && it.defData?.exportType == className }
			} else {
				athena.items.values.filter { it.primaryAssetType == filterType }
			}
			if (items.isEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage("You have no ${names[filterType]}.")).create()
			}
			return items
		}
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(argument("type", word())
			.executes { type(it.source, parseCosmeticType(getString(it, "type"))) }
		)
		.then(literal("exclusives")
			.executes { exclusives(it.source, EnumSet.of(ExclusivesType.EXCLUSIVE)) }
			.then(literal("favoriteall").executes { exclusivesUpdateFavorite(it.source, true) })
			.then(literal("unfavoriteall").executes { exclusivesUpdateFavorite(it.source, false) })
			.then(literal("test").requires(Rune::isBotDev).executes { exclusivesTest(it.source) })
		)
		.then(literal("uniques")
			.executes { exclusives(it.source, EnumSet.of(ExclusivesType.UNIQUE)) }
		)
		.then(literal("exclusivesanduniques")
			.executes { exclusives(it.source, EnumSet.allOf(ExclusivesType::class.java)) }
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
		.then(group("exclusives", "Shows your exclusive cosmetics in an image.")
			.then(subcommand("view", "Shows your exclusive cosmetics in an image.").executes { exclusives(it, EnumSet.of(ExclusivesType.EXCLUSIVE)) })
			.then(subcommand("favoriteall", "Favorites all of your exclusives.").executes { exclusivesUpdateFavorite(it, true) })
			.then(subcommand("unfavoriteall", "Unfavorites all of your exclusives.").executes { exclusivesUpdateFavorite(it, false) })
		)
		.then(subcommand("uniques", "Shows your \"unique\" cosmetics in an image.")
			.executes { exclusives(it, EnumSet.of(ExclusivesType.UNIQUE)) }
		)
		.then(subcommand("exclusives-and-uniques", "Shows your exclusive and \"unique\" cosmetics in an image.")
			.executes { exclusives(it, EnumSet.allOf(ExclusivesType::class.java)) }
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
		generateAndSendLockerImage(source, finalItems[choice], GenerateLockerImageParams(names[choice], icons[choice])).await()
			?: throw SimpleCommandExceptionType(LiteralMessage("You have no ${names[choice]}.")).create()
		return Command.SINGLE_SUCCESS
	}

	private fun type(source: CommandSourceStack, filterType: String): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val items = getLockerItems(source.api.profileManager.getProfileData("athena"), filterType)
		source.loading("Generating and uploading image")
		generateAndSendLockerImage(source, items, GenerateLockerImageParams(names[filterType], icons[filterType])).await()
		return Command.SINGLE_SUCCESS
	}

	private fun exclusives(source: CommandSourceStack, types: EnumSet<ExclusivesType>): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		CompletableFuture.allOf(
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "common_core"),
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
		).await()
		val commonCore = source.api.profileManager.getProfileData("common_core")
		val athena = source.api.profileManager.getProfileData("athena")
		val items = getExclusiveItems(setOf(commonCore, athena), types, false)
		source.loading("Generating and uploading image")
		generateAndSendLockerImage(source, items, GenerateLockerImageParams("Exclusives", "/Game/Athena/UI/Frontend/Art/T_UI_BP_BattleStar_L.T_UI_BP_BattleStar_L", exclusives = EXCLUSIVES_INFO)).await()
			?: throw SimpleCommandExceptionType(LiteralMessage("No Exclusives.")).create()
		return Command.SINGLE_SUCCESS
	}

	private fun exclusivesTest(source: CommandSourceStack): Int {
		val items = exclusives.values.map { FortItemStack(it.templateId, 1) }
		source.loading("Generating and uploading image")
		generateAndSendLockerImage(source, items, GenerateLockerImageParams("Exclusives", "/Game/Athena/UI/Frontend/Art/T_UI_BP_BattleStar_L.T_UI_BP_BattleStar_L", exclusives = EXCLUSIVES_INFO)).await()
			?: throw SimpleCommandExceptionType(LiteralMessage("No Exclusives.")).create()
		return Command.SINGLE_SUCCESS
	}

	private fun exclusivesUpdateFavorite(source: CommandSourceStack, favorite: Boolean): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val items = getExclusiveItems(setOf(athena), EnumSet.of(ExclusivesType.EXCLUSIVE), true)
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
		confirmationMsg.editMessageEmbeds(embed.setColor(COLOR_SUCCESS)
			.setDescription("✅ " + (if (favorite) "Favorited %,d exclusives!" else "Unfavorited %,d exclusives!").format(toFavorite.size))
			.build()).complete()
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
		val items = getLockerItems(source.api.profileManager.getProfileData("athena"), filterType)
		val queryAccountIds = mutableSetOf<String>()
		val entries = items.sortedWith(SimpleAthenaLockerItemComparator().apply { prioritizeFavorites = false })
			.map { LockerEntry(it, queryAccountIds) }
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

fun generateAndSendLockerImage(source: CommandSourceStack, ids: Collection<FortItemStack>?, params: GenerateLockerImageParams) = CompletableFuture.supplyAsync {
	if (ids.isNullOrEmpty()) {
		return@supplyAsync null
	}
	val items = ids.sortedWith(SimpleAthenaLockerItemComparator().apply { prioritizeFavorites = false; prioritizeExclusives = params.exclusives == EXCLUSIVES_DISTINGUISH })
	val start = System.currentTimeMillis()
	val image = generateLockerImage(items, params.withSource(source))
	val elapsed = System.currentTimeMillis() - start
	var output = image.toPngArray()
	var scale = 1f
	while (output.size > Message.MAX_FILE_SIZE && scale > 0.2f) {
		output = image.scale((image.width * scale).toInt(), (image.height * scale).toInt(), Image.SCALE_SMOOTH).toPngArray()
		//println("png size ${png.size} scale $scale")
		scale -= 0.2f
	}
	val name = params.name
	val message = MessageBuilder("**$name** (${Formatters.num.format(ids.size)})")
	if (DiscordBot.ENV == "dev") {
		message.append("\nRendered: ${elapsed}ms")
	}
	source.complete(message.build(), AttachmentUpload(output, "$name-${source.api.currentLoggedIn.id}.png"))
}

fun BufferedImage.toJpgArray(): ByteArray {
	val jpgWriter = ImageIO.getImageWritersByFormatName("jpg").next()
	val jpgWriterParam = jpgWriter.defaultWriteParam
	jpgWriterParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
	jpgWriterParam.compressionQuality = 0.7f
	val out = ByteArrayOutputStream()
	jpgWriter.output = MemoryCacheImageOutputStream(out)
	jpgWriter.write(null, IIOImage(this, null, null), jpgWriterParam)
	return out.toByteArray()
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
			descriptionParts.add("%s: %s".format(variantContainer.channelName, activeVariantDisplayName))
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
		is FortCosmeticVariantBackedByArray -> {
			val activeVariant = cosmeticVariant.getActive(backendVariant)
			val variantName = activeVariant?.VariantName?.format() ?: "**UNKNOWN SUBTYPE PLEASE REPORT**"
			variantName.ifEmpty { activeVariant?.backendVariantName }
		}
		is FortCosmeticFloatSliderVariant -> "%d/%d".format(cosmeticVariant.getActive(backendVariant).toInt(), cosmeticVariant.MaxParamValue.toInt())
		is FortCosmeticNumericalVariant -> Formatters.num.format(cosmeticVariant.getActive(backendVariant))
		is FortCosmeticProfileBannerVariant -> null // Always the currently equipped banner, cannot be displayed
		is FortCosmeticRichColorVariant -> {
			val activeColor = cosmeticVariant.getActive(backendVariant)
			val swatch = cosmeticVariant.InlineVariant.RichColorVar.ColorSwatchForChoices?.load<CustomDynamicColorSwatch>()
			val colorDef = swatch?.ColorPairs?.firstOrNull { it.ColorValue.r == activeColor.r && it.ColorValue.g == activeColor.g && it.ColorValue.b == activeColor.b && it.ColorValue.a == activeColor.a }
			colorDef?.ColorDisplayName?.format() ?: "#%08X".format(activeColor.toFColor(true).toPackedARGB())
		}
		else -> "**UNKNOWN TYPE PLEASE REPORT**"
	}

	operator fun component1() = cosmeticVariant
	operator fun component2() = backendVariant
}
