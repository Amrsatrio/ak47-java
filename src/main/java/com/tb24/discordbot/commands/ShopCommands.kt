package com.tb24.discordbot.commands

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.CatalogManager
import com.tb24.discordbot.GridSlot
import com.tb24.discordbot.createAttachmentOfIcons
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.EItemShopTileSize
import com.tb24.fn.model.EItemShopTileSize.*
import com.tb24.fn.model.assetdata.ESubGame
import com.tb24.fn.model.gamesubcatalog.CatalogDownload
import com.tb24.fn.model.gamesubcatalog.CatalogOffer.CatalogItemPrice
import com.tb24.fn.model.gamesubcatalog.EStoreCurrencyType
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.PopulatePrerolledOffers
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.AssetManager
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.fort.exports.CatalogMessaging
import me.fungames.jfortniteparse.fort.exports.FortMtxOfferData
import me.fungames.jfortniteparse.fort.exports.FortRarityData
import me.fungames.jfortniteparse.fort.objects.FortColorPalette
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.ue4.objects.core.math.FVector2D
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.EmbedBuilder
import okhttp3.OkHttpClient
import java.awt.*
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileReader
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.system.exitProcess

class ShopCommand : BrigadierCommand("shop", "Sends an image of today's item shop.", arrayOf("s")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			if (isUserAnIdiot(source)) {
				return@executes Command.SINGLE_SUCCESS
			}
			source.ensureSession()
			source.loading("Getting the shop")
			source.client.catalogManager.ensureCatalogData(source.api)
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val slots = mutableListOf<GridSlot>()
			for (section in source.client.catalogManager.athenaSections.values) {
				for (offer in section.items) {
					if (offer.prices.firstOrNull()?.currencyType == EStoreCurrencyType.RealMoney) {
						continue
					}
					var image: BufferedImage? = null
					var title: String? = null
					var rarity: EFortRarity? = null
					offer.itemGrants.firstOrNull()?.let { item ->
						image = item.getPreviewImagePath()?.load<UTexture2D>()?.toBufferedImage()
						title = item.displayName
						rarity = item.defData.Rarity
					}
					if (!offer.displayAssetPath.isNullOrEmpty()) {
						AssetManager.INSTANCE.provider.loadObject<FortMtxOfferData>(offer.displayAssetPath)?.let {
							(it.BackgroundImage?.ResourceObject?.value as? UTexture2D)?.run { image = toBufferedImage() }
							it.DisplayName?.run { title = format() }
						}
					}
					offer.title?.let { title = it }
					slots.add(GridSlot(
						image = image,
						name = if (title.isNullOrEmpty()) offer.devName else title,
						rarity = if (offer.getMeta("HideRarityBorder").equals("true", true)) null else rarity,
						index = offer.__ak47_index
					))
				}
			}
			val tz = TimeZone.getTimeZone("UTC")
			val now = Date()
			val fileName = "shop-${SimpleDateFormat("dd-MM-yyyy").apply { timeZone = tz }.format(now)}.png"
			source.channel.sendMessage("Battle Royale Item Shop (%s)".format(DateFormat.getDateInstance().apply { timeZone = tz }.format(now))).addFile(createAttachmentOfIcons(slots, "shop"), fileName).complete()
			source.loadingMsg!!.delete().queue()
			Command.SINGLE_SUCCESS
		}
}

class ShopTextCommand : BrigadierCommand("shoptext", "Sends the current item shop items as a text.", arrayOf("st")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { executeShopText(it.source, ESubGame.Athena) }
}

class CampaignShopCommand : BrigadierCommand("stwshop", "Sends the current Save the World item shop items as a text.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { executeShopText(it.source, ESubGame.Campaign) }
}

class ShopDumpCommand : BrigadierCommand("shopdump", "Sends the current item shop as a JSON.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			if (source.api.userToken == null) {
				source.session = source.client.internalSession
			}
			val lang = "en"
			val data = source.api.okHttpClient.newCall(source.api.fortniteService.storefrontCatalog(lang).request()).exec().body()!!.charStream().use {
				JsonParser.parseReader(it)
			}
			val df = SimpleDateFormat("dd-MM-yyyy").apply { timeZone = TimeZone.getTimeZone("UTC") }
			source.channel.sendFile(GsonBuilder().setPrettyPrinting().create().toJson(data).toByteArray(), "shop-%s-%s.json".format(df.format(Date()), lang)).complete()
			Command.SINGLE_SUCCESS
		}
}

fun executeShopText(source: CommandSourceStack, subGame: ESubGame): Int {
	if (subGame == ESubGame.Athena && isUserAnIdiot(source)) {
		return Command.SINGLE_SUCCESS
	}
	source.ensureSession()
	source.loading("Getting the shop")
	val catalogManager = source.client.catalogManager
	val profileManager = source.api.profileManager
	catalogManager.ensureCatalogData(source.api)
	CompletableFuture.allOf(
		profileManager.dispatchClientCommandRequest(QueryProfile()),
		if (subGame == ESubGame.Campaign) {
			profileManager.dispatchClientCommandRequest(PopulatePrerolledOffers(), "campaign")
		} else {
			profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
		}
	).await()
	val sections = if (subGame == ESubGame.Campaign) catalogManager.campaignSections else catalogManager.athenaSections.values
	val contents = arrayOfNulls<List<String>>(sections.size)
	val prices = mutableMapOf<String, CatalogItemPrice>()
	for ((i, section) in sections.withIndex()) {
		val lines = mutableListOf<String>()
		for (catalogEntry in section.items) {
/*			if (catalogEntry.shouldBeSkipped(commonCore)) {
				continue
			}*/
			if (catalogEntry.prices.isEmpty() || catalogEntry.prices.first().currencyType == EStoreCurrencyType.RealMoney) continue
			val sd = catalogEntry.holder().apply { resolve(profileManager) }
			lines.add("${(catalogEntry.__ak47_index + 1)}. ${sd.friendlyName}${if (sd.owned) " ✅" else ""}")
			catalogEntry.prices.forEach { prices.putIfAbsent(it.currencyType.name + ' ' + it.currencySubType, it) }
		}
		contents[i] = lines
	}
	val embed = EmbedBuilder()
		.setColor(0x0099FF)
		.setTitle(if (subGame == ESubGame.Campaign) "⚡ " + "Save the World Item Shop" else "☂ " + "Battle Royale Item Shop")
	if (source.session.id != "__internal__") {
		embed.setDescription("Use `${source.prefix}buy` or `${source.prefix}gift` to perform operations with these items.\n✅ = Owned")
			.addField(if (prices.size == 1) "Balance" else "Balances", prices.values.joinToString(" \u00b7 ") { it.getAccountBalanceText(profileManager) }, false)
	}
	for ((i, section) in sections.withIndex()) {
		if (contents[i]!!.isEmpty()) {
			continue
		}
		embed.addFieldSeparate(section.sectionData.sectionDisplayName ?: "", contents[i], 0)
	}
	source.complete(null, embed.build())
	return Command.SINGLE_SUCCESS
}

private fun isUserAnIdiot(source: CommandSourceStack): Boolean {
	if (source.channel.idLong == 709667951300706385L || source.channel.idLong == 708845713592811603L) {
		source.message.reply("Hey ${source.author.asMention}, in this server there is an <#702307657989619744> channel.\nIf you believe that it is outdated, you can DM one of us to update it.").complete()
		return true
	}
	return false
}

fun main() {
	generateShopImage()
}

fun generateShopImage() {
	AssetManager.INSTANCE.loadPaks()
	val rarityData = AssetManager.INSTANCE.provider.loadObject<FortRarityData>("/Game/Balance/RarityData.RarityData")!!
	val store = FileReader("D:\\Downloads\\shop-25-12-2020-en.json").use { EpicApi.GSON.fromJson(it, CatalogDownload::class.java) }
	val catalogManager = CatalogManager()
	catalogManager.ensureCatalogData(EpicApi(OkHttpClient()))
	val sections = catalogManager.athenaSections.values.filter {
		if (it.items.isEmpty()) {
			false
		} else {
			it.items.sortByDescending { it.sortPriority ?: 0 }
			true
		}
	}
	println(store.storefronts.size)
	val noDisplayNameCountC = 0f
	val noDisplayNameCount = 0f
	val firstSection = 300f
	val itemSpacingX = 25f
	val itemSpacingY = 25f
	val sectionSpacing = 92f
	val normalBaseSizeX = 318f
	val normalBaseSizeY = 551f
	val rowStartX = 98f
	val finalSpacingX = 132f
	val finalSpacingY = 92f
	val tileSizes = mapOf(
		Mini to FVector2D(normalBaseSizeX, normalBaseSizeY / 3f - itemSpacingY / 1.5f),
		Small to FVector2D(normalBaseSizeX, normalBaseSizeY / 2 - itemSpacingY / 2),
		Normal to FVector2D(normalBaseSizeX, normalBaseSizeY),
		DoubleWide to FVector2D(normalBaseSizeX * 2f + itemSpacingX, normalBaseSizeY),
		TripleWide to FVector2D(normalBaseSizeX * 3f + itemSpacingX, normalBaseSizeY)
	) //better

	var sectionsNum = 0
	var sectionsNum2 = 0
	var imageX = 0f
	var imageY = 0f

	class FViolatorColorPalette(val outline: Int, val inside: Int, val text: Int)

	val violatorPalettes = mapOf(
		EViolatorIntensity.High to FViolatorColorPalette(0xFFFFFF, 0xFFFF00, 0x00062B),
		EViolatorIntensity.Low to FViolatorColorPalette(0xFF2C78, 0xCF0067, 0xFFFFFF),
		// medium is not implemented
	)

	val catalogMessages = AssetManager.INSTANCE.provider.loadObject<CatalogMessaging>("/Game/Athena/UI/Frontend/CatalogMessages.CatalogMessages")!!

	for (section in sections) {
		++sectionsNum

		var rowStartY = itemSpacingY + sectionsNum * normalBaseSizeY - normalBaseSizeY + sectionsNum * sectionSpacing + firstSection

		rowStartY -= (noDisplayNameCountC * sectionSpacing)
		// var rowStartY = sectionsNum === 1 ?itemSpacingY + sectionsNum * normalBaseSizeY - normalBaseSizeY + sectionsNum * sectionSpacing+firstSection :itemSpacingY + sectionsNum * normalBaseSizeY - normalBaseSizeY + sectionsNum * sectionSpacing
		// var normalBaseSizeY = 404

		var sortingHelper = FShopSortingHelper(Normal, mutableMapOf())
		var sortingX = rowStartX
		var sortingY = rowStartY
		var limit = normalBaseSizeY + itemSpacingY * 2 + rowStartY

		for (entry_ in section.items) {
			val entry = entry_.holder()
			val tileSize = EItemShopTileSize.valueOf(entry.getMeta("TileSize") ?: throw RuntimeException("h!"))
			val currentTileSize = tileSizes[tileSize]!!
			if (sortingY + currentTileSize.y > limit) {
				sortingY = rowStartY
				sortingX += currentTileSize.x + itemSpacingX
				sortingHelper.quants[tileSize] = 0
				when (tileSize) {
					Mini -> {
						if (sortingHelper.quants[tileSize]!! >= 3) {
							sortingX += currentTileSize.x + itemSpacingX
							sortingY -= (currentTileSize.y + itemSpacingY) * 2
							sortingHelper.quants[tileSize] = 0
						} else {
							sortingY += currentTileSize.y + itemSpacingY
						}
					}
					Small -> {
						if (sortingHelper.quants[tileSize]!! >= 2) {
							sortingX += currentTileSize.x + itemSpacingX
							sortingY -= currentTileSize.y + itemSpacingY
							sortingHelper.quants[tileSize] = 0
						} else {
							sortingY += currentTileSize.y + itemSpacingY
						}
					}
					Normal -> {
						sortingX += currentTileSize.x + itemSpacingX
					}
					DoubleWide -> {
						sortingX += currentTileSize.x + itemSpacingX
					}
					TripleWide -> {
						sortingX += currentTileSize.x + itemSpacingX
					}
				}
			}
			if (sortingY < rowStartY) {
				sortingY = rowStartY
			}
			sortingHelper.name = tileSize

			imageX = if (imageX > sortingX + currentTileSize.x + itemSpacingX) imageX else sortingX + currentTileSize.x + itemSpacingX
			imageY = if (imageY > sortingY + currentTileSize.y + itemSpacingY) imageY else sortingY + currentTileSize.y + itemSpacingY
		}
	}

	imageX -= normalBaseSizeX + itemSpacingX * 2//bad fix kek
	imageX += finalSpacingX
	imageY += finalSpacingY - itemSpacingY
	File("out.png").writeBytes(createAndDrawCanvas(imageX.toInt() * 2, imageY.toInt()) { ctx ->
		val fac = .3f
		ctx.paint = RadialGradientPaint(
			Rectangle(imageX.toInt(), imageY.toInt()).apply { grow((imageX * fac).toInt(), (imageY * fac).toInt()) },
			floatArrayOf(0f, 1f),
			arrayOf(0xFF099AFE.awtColor(), 0xFF0942B4.awtColor()),
			MultipleGradientPaint.CycleMethod.NO_CYCLE)
		ctx.fillRect(0, 0, imageX.toInt(), imageY.toInt())

		ctx.font = ResourcesContext.burbankBigCondensedBlack.deriveFont(400f)
		ctx.drawString("ITEM SHOP", imageX.toInt() / 2 + 28 + 330 / 2, 350)

		for (section in sections) {
			++sectionsNum2
			var rowStartY = itemSpacingY + sectionsNum2 * normalBaseSizeY - normalBaseSizeY + sectionsNum2 * sectionSpacing + firstSection

			rowStartY -= noDisplayNameCount * sectionSpacing
			var sortingHelper = FShopSortingHelper(Normal, mutableMapOf())
			var sortingX = rowStartX
			var sortingY = rowStartY
			var limit = normalBaseSizeY + itemSpacingY * 2 + rowStartY

			ctx.color = 0x89F0FF.awtColor()
			ctx.font = ctx.font.deriveFont(32f)
			val dn = section.sectionData.sectionDisplayName ?: ""
			ctx.drawString(dn, sortingX + 27, sortingY - 20)

			if (section.sectionData.bShowTimer) {
				val textWidth = ctx.fontMetrics.stringWidth(dn)
				drawTimer(ctx, (sortingX + 27 + textWidth + 5 + 6).toInt(), (sortingY - 18 - 30).toInt())
			}

			ctx.color = Color.BLACK
			for (entry_ in section.items) {
				val entry = entry_.holder()
				val tileSize = EItemShopTileSize.valueOf(entry.getMeta("TileSize") ?: throw RuntimeException("h!"))
				val currentTileSize = tileSizes[tileSize]!!

				if (sortingY + currentTileSize.y > limit) {
					sortingY = rowStartY

					sortingX += currentTileSize.x + itemSpacingX
					sortingHelper.quants[tileSize] = 0
				}
				val firstItem = entry.ce.itemGrants.firstOrNull() ?: continue
				val xOffset = when {
					firstItem.primaryAssetType == "AthenaDance" -> tileSize.offsets().emoteX
					firstItem.primaryAssetType == "AthenaGlider" && tileSize == Small -> tileSize.offsets().gliderX
					else -> 0
				}
				val seriesExists = false
				firstItem.defData?.Series?.value?.let {
					// fuck the series bg
				}
				val endsWithIcon = false//imageLink.endsWith("icon.png")
				var multi = if (tileSize == Normal && endsWithIcon) 0.5f else 1.0f
				multi = if (tileSize == Small && firstItem.primaryAssetType != "AthenaDance" && firstItem.primaryAssetType != "AthenaItemWrap") 2f else multi
				multi = if (tileSize == Small && firstItem.primaryAssetType == "AthenaDance") 1.4f else multi
				multi = if (tileSize == Normal && firstItem.primaryAssetType == "AthenaDance" && !endsWithIcon) 1.1f else multi
				multi = if (tileSize == Small && firstItem.primaryAssetType == "AthenaCharacter" && endsWithIcon) 1.2f else multi
				multi = if (tileSize == Small && firstItem.primaryAssetType == "AthenaPickaxe" && endsWithIcon) 1f else multi
				multi = if (tileSize == Small && firstItem.primaryAssetType == "AthenaGlider" && endsWithIcon) 1f else multi
				val multi2 = multi * 2

				// TODO draw item img
				val palette = rarityData.forRarity(firstItem.rarity)

				// rarity
				ctx.color = palette.Color1.toColor()
				val path = Path2D.Float()
				path.moveTo(sortingX, sortingY + currentTileSize.y - 72)
				path.lineTo(sortingX + currentTileSize.x, sortingY + currentTileSize.y - 82)
				path.lineTo(sortingX + currentTileSize.x, sortingY + currentTileSize.y - 74)
				path.lineTo(sortingX, sortingY + currentTileSize.y - 67)
				path.closePath()
				ctx.fill(path)

				// text bg
				ctx.color = 0x1E1E1E.awtColor()
				path.reset()
				path.moveTo(sortingX, sortingY + currentTileSize.y - 67)
				path.lineTo(sortingX + currentTileSize.x, sortingY + currentTileSize.y - 74)
				path.lineTo(sortingX + currentTileSize.x, sortingY + currentTileSize.y)
				path.lineTo(sortingX, sortingY + currentTileSize.y)
				path.closePath()
				ctx.fill(path)

				// bottom
				ctx.color = 0x0E0E0E.awtColor()
				path.reset()
				path.moveTo(sortingX, sortingY + currentTileSize.y - 26)
				path.lineTo(sortingX + currentTileSize.x, sortingY + currentTileSize.y - 28)
				path.lineTo(sortingX + currentTileSize.x, sortingY + currentTileSize.y)
				path.lineTo(sortingX, sortingY + currentTileSize.y)
				path.closePath()
				ctx.fill(path)

				val priceNum = 9999 // later
				val priceText = Formatters.num.format(priceNum)

				ctx.color = 0xA7B8BC.awtColor()
				ctx.font = ResourcesContext.burbankBigRegularBlack.deriveFont(12f)
				ctx.drawString(priceText, sortingX + currentTileSize.x - 46, sortingY + currentTileSize.y - 9)

				ctx.color = Color.WHITE
				ctx.font = ctx.font.deriveFont(17f)
				ctx.drawString(firstItem.displayName, sortingX + currentTileSize.x / 2, sortingY + currentTileSize.y - 40)

				val violatorIntensity = runCatching { EViolatorIntensity.valueOf(entry.getMeta("ViolatorIntensity")!!) }.getOrNull()
				if (violatorIntensity != null) {
					check(violatorIntensity != EViolatorIntensity.Medium) {
						"medium is not implemented"
					}
					ctx.font = ctx.font.deriveFont(14f)

					val violatorTag = entry.getMeta("ViolatorTag")
					val text = catalogMessages.StoreToast_Body[violatorTag]?.format() ?: violatorTag ?: "?!?!?!"
					// yeah dynamic bundle later lets get the basic stuff first
					val xOffsetText = ctx.fontMetrics.stringWidth(text)

					//outline
					ctx.color = violatorPalettes[violatorIntensity]!!.outline.awtColor()
					path.reset()
					path.moveTo(sortingX - 12, sortingY - 9)
					path.lineTo(sortingX + 22 + xOffsetText, sortingY - 12)
					path.lineTo(sortingX + 14 + xOffsetText, sortingY + 27)
					path.lineTo(sortingX - 8, sortingY + 26)
					path.closePath()
					ctx.fill(path)

					//inside
					ctx.color = violatorPalettes[violatorIntensity]!!.inside.awtColor()
					path.reset()
					path.moveTo(sortingX - 6, sortingY - 4)
					path.lineTo(sortingX + 15 + xOffsetText, sortingY - 6)
					path.lineTo(sortingX + 9 + xOffsetText, sortingY + 22)
					path.lineTo(sortingX - 3, sortingY + 21)
					path.closePath()
					ctx.fill(path)

					//text
					ctx.color = violatorPalettes[violatorIntensity]!!.text.awtColor()
					ctx.drawString(text, (sortingX - 6) + (sortingX + 10 + xOffsetText - (sortingX - 6)) / 2, sortingY - 10 + (sortingY + 26 - (sortingY - 10)) / 2 + ctx.fontMetrics.ascent / 2)
				}
				val newQuant = sortingHelper.quants.getOrPut(tileSize) { 0 } + 1
				sortingHelper.quants[tileSize] = newQuant

				when (tileSize) {
					Mini -> {
						if (newQuant >= 3) {
							sortingX += currentTileSize.x + itemSpacingX
							sortingY -= (currentTileSize.y + itemSpacingY) * 2
							sortingHelper.quants[tileSize] = 0
						} else {
							sortingY += currentTileSize.y + itemSpacingY
						}
					}
					Small -> {
						if (newQuant >= 2) {
							sortingX += currentTileSize.x + itemSpacingX
							sortingY -= currentTileSize.y + itemSpacingY
							sortingHelper.quants[tileSize] = 0
						} else {
							sortingY += currentTileSize.y + itemSpacingY
						}
					}
					Normal -> {
						sortingX += currentTileSize.x + itemSpacingX
					}
					DoubleWide -> {
						sortingX += currentTileSize.x + itemSpacingX
					}
					TripleWide -> {
						sortingX += currentTileSize.x + itemSpacingX
					}
				}
				if (sortingY < rowStartY) {
					sortingY = rowStartY
				}
				sortingHelper.name = tileSize
				imageX = if (imageX > sortingX + currentTileSize.x + itemSpacingX) imageX else sortingX + currentTileSize.x + itemSpacingX
				imageY = if (imageY > sortingY + currentTileSize.y + itemSpacingY) imageY else sortingY + currentTileSize.y + itemSpacingY
			}
		}
	}.toPngArray())
	exitProcess(0)
}

fun drawTimer(ctx: Graphics2D, iconX: Int, iconY: Int, width: Int = 0, height: Int = 0, scale: Float = 1f) {
	val fillColor = ctx.color.rgb
	val timerIcon = ImageIO.read(File("C:\\Users\\satri\\Desktop\\ui_timer_64x.png"))
	val tW = if (width <= 0f) timerIcon.width else width
	val tH = if (height <= 0f) timerIcon.height else height
	val pixels = timerIcon.getRGB(0, 0, tW, tH, null, 0, tW)
	val handPixels = IntArray(pixels.size)
	for ((i, it) in pixels.withIndex()) {
		var outAlpha = (it shr 16) and 0xFF // red channel: base
		outAlpha -= (it shr 8) and 0xFF // green channel: inner
		outAlpha = max(outAlpha, 0)
		pixels[i] = (outAlpha shl 24) or fillColor
		handPixels[i] = ((it and 0xFF) shl 24) or fillColor // blue channel: hand
	}
	val frame = BufferedImage(tW, tH, BufferedImage.TYPE_INT_ARGB)
	frame.setRGB(0, 0, tW, tH, pixels, 0, tW)
	val hand = BufferedImage(tW, tH, BufferedImage.TYPE_INT_ARGB)
	hand.setRGB(0, 0, tW, tH, handPixels, 0, tW)
	val iconSize = (28 * scale).toInt()
	ctx.drawImage(frame, iconX, iconY, iconSize, iconSize, null)
	val saveT = ctx.transform
	val oX = 0.49 * iconSize
	val oY = 0.575 * iconSize
	val currentSecondsInHour = (System.currentTimeMillis() / 1000) % (60 * 60)
	ctx.rotate(Math.toRadians(currentSecondsInHour.toDouble() / (60 * 60) * 360), iconX + oX, iconY + oY)
	ctx.drawImage(hand, iconX, iconY, iconSize, iconSize, null)
	ctx.transform = saveT
}

class FShopSortingHelper(var name: EItemShopTileSize, val quants: MutableMap<EItemShopTileSize, Int>)

fun FortRarityData.forRarity(rarity: EFortRarity): FortColorPalette {
	val h = RarityCollection[rarity.ordinal]
	return FortColorPalette().apply {
		Color1 = h.Color1
		Color2 = h.Color2
		Color3 = h.Color3
		Color4 = h.Color4
		Color5 = h.Color5
	}
}

enum class EViolatorIntensity {
	Low,
	Medium,
	High
}

class FOffsets(val emoteX: Int, val gliderX: Int)

fun EItemShopTileSize.offsets() = when (this) {
	Mini -> FOffsets(0, 0)
	Small -> FOffsets(43, 15)
	Normal -> FOffsets(30, 0)
	DoubleWide -> FOffsets(0, 0)
	TripleWide -> FOffsets(0, 0)
}