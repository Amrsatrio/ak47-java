package com.tb24.discordbot.images

import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.commands.OfferDisplayData
import com.tb24.discordbot.managers.CatalogManager
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.EItemShopTileSize
import com.tb24.fn.model.FortCmsData
import com.tb24.fn.model.gamesubcatalog.CatalogDownload
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.ECatalogOfferType
import com.tb24.fn.model.gamesubcatalog.EStoreCurrencyType
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.CatalogMessaging
import me.fungames.jfortniteparse.ue4.assets.exports.mats.UMaterialInstance
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.ue4.objects.core.math.FVector2D
import me.fungames.jfortniteparse.util.drawCenteredString
import me.fungames.jfortniteparse.util.toPngArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.*
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileReader
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.system.exitProcess

fun main() {
	AssetManager.INSTANCE.loadPaks()
	val catalogManager = CatalogManager()
	catalogManager.catalogData = FileReader("D:/Downloads/shop-09-05-2021-en.json").use { EpicApi.GSON.fromJson(it, CatalogDownload::class.java) }
	catalogManager.sectionsData = OkHttpClient().newCall(Request.Builder().url("https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game/shop-sections").build()).exec().to<FortCmsData.ShopSectionsData>()
	catalogManager.validate()
	File("out.png").writeBytes(generateShopImage(catalogManager, 2).toPngArray())
	exitProcess(0)
}

val catalogMessages by lazy { loadObject<CatalogMessaging>("/Game/Athena/UI/Frontend/CatalogMessages.CatalogMessages")!! }

fun generateShopImage(catalogManager: CatalogManager, grid: Int = 0): BufferedImage {
	if (grid > 0) {
		val entries = mutableListOf<FShopEntryContainer>()
		for (section in catalogManager.athenaSections.values) {
			val sectionId = section.sectionData.sectionId
			if (sectionId != null && sectionId.equals("battlepass", true)) {
				continue
			}
			for (offer in section.items) {
				if (offer.prices.firstOrNull()?.currencyType == EStoreCurrencyType.RealMoney) {
					continue
				}
				entries.add(FShopEntryContainer(offer, section))
			}
		}
		if (grid == 1) {
			throw UnsupportedOperationException("Legacy shop style is no longer supported")
		} else if (grid == 2) {
			val columns = ceil(sqrt(entries.size.toDouble())).toInt()
			val padding = 6
			val doublePadding = 2 * padding
			val tileSize = 256 + doublePadding
			val imageW = columns * tileSize + doublePadding
			val imageH = ceil(entries.size.toDouble() / columns.toDouble()).toInt() * tileSize + doublePadding
			return createAndDrawCanvas(imageW, imageH) { ctx ->
				ctx.drawStretchedRadialGradient(0xFF099AFE, 0xFF0942B4, 0, 0, imageW, imageH)
				for ((i, entry) in entries.withIndex()) {
					entry.x = doublePadding + (i % columns * tileSize).toFloat()
					entry.y = doublePadding + (i / columns * tileSize).toFloat()
					entry.w = tileSize.toFloat() - padding * 2
					entry.h = tileSize.toFloat() - padding * 2
					entry.draw(ctx)
				}
			}
		}
	}
	val itemSpacingH = 24f
	val itemSpacingV = 24f
	val sectionSpacing = 72f
	val normalTileW = 318f
	val normalTileH = 551f
	val sectionMarginH = 40f
	val sectionMarginV = 40f
	val tileSizes = mapOf(
		EItemShopTileSize.Mini to FVector2D(normalTileW, normalTileH / 3f - itemSpacingV / 1.5f),
		EItemShopTileSize.Small to FVector2D(normalTileW, normalTileH / 2f - itemSpacingV / 2f),
		EItemShopTileSize.Normal to FVector2D(normalTileW, normalTileH),
		EItemShopTileSize.DoubleWide to FVector2D(normalTileW * 2f + itemSpacingH, normalTileH),
		EItemShopTileSize.TripleWide to FVector2D(normalTileW * 3f + itemSpacingH, normalTileH)
	)

	var imageW = 0f
	var imageH = 0f

	val sectionsToDisplay = if (true) catalogManager.athenaSections.values.filter {
		val sid = it.sectionData.sectionId
		it.items.isNotEmpty() && sid != "LimitedTime" && sid != "Battlepass" && sid != "Subscription"
	}
	else catalogManager.athenaSections.values.filter { it.items.isNotEmpty() }.subList(0, 2)
	val titleFont = ResourcesContext.burbankBigRegularBlack.deriveFont(Font.ITALIC, 160f)
	val titleText = "ITEM SHOP"
	val sectionContainers = mutableListOf<FShopSectionContainer>()

	// region Measure & Layout
	val dummyFrc = FontRenderContext(AffineTransform(), true, true)
	val titleBounds = titleFont.getStringBounds(titleText, dummyFrc)
	val titleHeight = titleBounds.height.toFloat()

	for ((i, section) in sectionsToDisplay.withIndex()) {
		val rowStartY = titleHeight + i * (normalTileH + sectionSpacing)
		val layoutHelper = FShopLayoutHelper(EItemShopTileSize.Normal, mutableMapOf())
		var tileX = sectionMarginH
		var tileY = rowStartY
		val limit = normalTileH + itemSpacingV * 2 + rowStartY
		val sectionContainer = FShopSectionContainer(section).apply { x = tileX; y = tileY }
		sectionContainers.add(sectionContainer)

		for (entry in section.items) {
			val tileSize = EItemShopTileSize.valueOf(entry.getMeta("TileSize") ?: throw RuntimeException("No TileSize specified"))
			val currentTileSize = tileSizes[tileSize]!!
			val tileW = currentTileSize.x
			val tileH = currentTileSize.y
			if (tileY + tileH > limit) {
				tileY = rowStartY
				tileX += tileW + itemSpacingH
				layoutHelper.quants[tileSize] = 0
			}
			val entryContainer = FShopEntryContainer(entry, section).apply {
				x = tileX; y = tileY
				w = tileW; h = tileH
				this.tileSize = tileSize
			}
			sectionContainer.entries.add(entryContainer)
			val newQuant = layoutHelper.quants.getOrPut(tileSize) { 0 } + 1
			layoutHelper.quants[tileSize] = newQuant
			when (tileSize) {
				EItemShopTileSize.Mini ->
					if (layoutHelper.quants[tileSize]!! >= 3) {
						tileX += tileW + itemSpacingH
						tileY -= (tileH + itemSpacingV) * 2
						layoutHelper.quants[tileSize] = 0
					} else {
						tileY += tileH + itemSpacingV
					}
				EItemShopTileSize.Small ->
					if (layoutHelper.quants[tileSize]!! >= 2) {
						tileX += tileW + itemSpacingH
						tileY -= tileH + itemSpacingV
						layoutHelper.quants[tileSize] = 0
					} else {
						tileY += tileH + itemSpacingV
					}
				EItemShopTileSize.Normal -> tileX += tileW + itemSpacingH
				EItemShopTileSize.DoubleWide -> tileX += tileW + itemSpacingH
				EItemShopTileSize.TripleWide -> tileX += tileW + itemSpacingH
			}
			if (tileY < rowStartY) {
				tileY = rowStartY
			}
			layoutHelper.name = tileSize

			imageW = max(imageW, tileX - itemSpacingH)
			imageH = max(imageH, tileY + tileH)
		}
	}

	imageW += sectionMarginH
	imageH += sectionMarginV
	// endregion

	// region Draw
	return createAndDrawCanvas(imageW.toInt(), imageH.toInt()) { ctx ->
		// Background
		ctx.drawStretchedRadialGradient(0xFF099AFE, 0xFF0942B4, 0, 0, imageW.toInt(), imageH.toInt())

		// Title
		if (false) {
			ctx.color = Color.BLACK
			ctx.fillRect(0, 0, titleBounds.width.toInt(), titleBounds.height.toInt())
		}
		ctx.font = titleFont
		ctx.color = Color.WHITE
		ctx.drawCenteredString(titleText, imageW.toInt() / 2, ctx.fontMetrics.ascent)

		// Sections
		sectionContainers.forEach { it.draw(ctx) }

		// Attribution
		ctx.font = ResourcesContext.burbankSmallBold.deriveFont(Font.ITALIC, 16f)
		ctx.color = 0x7FFFFFFF.awtColor()
		val attribText = "Original code by Otavio, adapted and improved by tb24"
		ctx.drawCenteredString(attribText, (imageW / 2).toInt(), (imageH - 8).toInt())
	}
	// endregion
}

fun Graphics2D.drawStretchedRadialGradient(innerColor: Number, outerColor: Number, x: Int, y: Int, w: Int, h: Int, scale: Float = .3f) {
	paint = RadialGradientPaint(
		Rectangle(x, y, w, h).apply { grow((w * scale).toInt(), (h * scale).toInt()) },
		floatArrayOf(0f, 1f),
		arrayOf(innerColor.awtColor(), outerColor.awtColor()),
		MultipleGradientPaint.CycleMethod.NO_CYCLE)
	fillRect(x, y, w, h)
}

fun Graphics2D.drawTimer(iconX: Int, iconY: Int, iconSize: Int = 28) {
	val fillColor = color.rgb and 0xFFFFFF
	val timerIcon = loadObject<UTexture2D>("/Game/UI/Icons/T_UI_Timer_64x.T_UI_Timer_64x")!!.toBufferedImage()
	val tW = timerIcon.width
	val tH = timerIcon.height
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
	drawImage(frame, iconX, iconY, iconSize, iconSize, null)
	val saveT = transform
	val oX = 0.49 * iconSize
	val oY = 0.575 * iconSize
	val currentSecondsInHour = (System.currentTimeMillis() / 1000) % (60 * 60)
	rotate(Math.toRadians(currentSecondsInHour.toDouble() / (60 * 60) * 360), iconX + oX, iconY + oY)
	drawImage(hand, iconX, iconY, iconSize, iconSize, null)
	transform = saveT
}

class FViolatorColorPalette(val outline: Int, val inside: Int, val text: Int)

class FShopLayoutHelper(var name: EItemShopTileSize, val quants: MutableMap<EItemShopTileSize, Int>)

class FShopSectionContainer(val section: CatalogManager.ShopSection) {
	var x = 0f
	var y = 0f
	val entries = mutableListOf<FShopEntryContainer>()

	fun draw(ctx: Graphics2D) {
		ctx.color = Color.WHITE
		ctx.font = ResourcesContext.burbankBigRegularBlack.deriveFont(Font.ITALIC, 40f)
		val sectionTitleText = section.sectionData.sectionDisplayName?.toUpperCase() ?: ""
		ctx.drawString(sectionTitleText, x + 27, y - 18)

		if (section.sectionData.bShowTimer) {
			ctx.color = 0x89F0FF.awtColor()
			val textWidth = ctx.fontMetrics.stringWidth(sectionTitleText)
			ctx.drawTimer((x + 27 + textWidth + 5 + 6).toInt(), (y - 46).toInt(), 32)
		}

		ctx.color = Color.BLACK
		entries.forEach { it.draw(ctx) }
	}
}

val mtxIcon by lazy { loadObject<UTexture2D>("/Game/UI/Foundation/Textures/Icons/Items/T-Items-MTX.T-Items-MTX")!! }

class FShopEntryContainer(val offer: CatalogOffer, val section: CatalogManager.ShopSection) {
	companion object {
		val violatorPalettes = mapOf(
			EViolatorIntensity.Low to FViolatorColorPalette(0xFF2C78, 0xCF0067, 0xFFFFFF),
			EViolatorIntensity.Medium to FViolatorColorPalette(0xFF2C78, 0xCF0067, 0xFFFFFF), // TODO test medium
			EViolatorIntensity.High to FViolatorColorPalette(0xFFFFFF, 0xFFFF00, 0x00062B),
		)
	}

	var x = 0f
	var y = 0f
	var w = 0f
	var h = 0f
	var tileSize = EItemShopTileSize.Normal
	val displayData = OfferDisplayData(offer, true)

	fun draw(ctx: Graphics2D) {
		val offer = offer.holder()
		//println("\ndrawing ${offerContainer.displayData.title}")
		val firstItem = offer.ce.itemGrants.firstOrNull() ?: return
		val firstItemType = firstItem.primaryAssetType
		/*val xOffset = when {
			firstItemType == "AthenaDance" -> tileSize.offsets().emoteX
			firstItemType == "AthenaGlider" && tileSize == EItemShopTileSize.Small -> tileSize.offsets().gliderX
			else -> 0
		}*/
		val artificialScale = when {
			tileSize == EItemShopTileSize.Small -> when (firstItemType) {
				"AthenaCharacter", "AthenaPickaxe", "AthenaGlider" -> 1f
				"AthenaDance", "AthenaItemWrap" -> 0.7f
				else -> 0.5f
			}
			tileSize == EItemShopTileSize.Normal && firstItemType == "AthenaDance" -> 0.8f
			else -> 1f
		}

		val p = displayData.presentationParams
		val bgColorA = p?.vector?.get("Background_Color_A") ?: 0xFF000000.toInt()
		val bgColorB = p?.vector?.get("Background_Color_B") ?: 0xFF000000.toInt()

		val gradientSize = p?.scalar?.get("Gradient_Size") ?: 50f
		val gradientX = p?.scalar?.get("Gradient_Position_X") ?: 50f
		val gradientY = p?.scalar?.get("Gradient_Position_Y") ?: 50f

		/*val spotlightSize = p.scalar["Spotlight_Size"] ?: 50f
		val spotlightHardness = p.scalar["Spotlight_Hardness"]
		val spotlightIntensity = p.scalar["Spotlight_Intensity"]
		val spotlightX = p.scalar["Spotlight_Position_X"]
		val spotlightY = p.scalar["Spotlight_Position_Y"]

		val fallOffColor = p.vector["FallOff_Color"]!!
		val fallOffColorFillPct = p.vector["FallOffColor_Fill_Percent"]
		val fallOffPosition = p.scalar["FallOffColor_Postion"]!!*/

		// base radial gradient background
		//println("Gradient x=$gradientX y=$gradientY size=$gradientSize")
		ctx.paint = RadialGradientPaint(
			x + (gradientX / 100f) * w, y + (gradientY / 100f) * h,
			(gradientSize / 100f) * max(w, h),
			floatArrayOf(0f, 1f),
			arrayOf(bgColorB.awtColor(), bgColorA.awtColor())
		)
		ctx.fillRect(x.toInt(), y.toInt(), w.toInt(), h.toInt())

		// spotlight, needs more research
		/*if (spotlightX != null && spotlightY != null) {
			println("Spotlight x=$spotlightX y=$spotlightY hardness=$spotlightHardness intensity=$spotlightIntensity size=$spotlightSize")
			ctx.paint = RadialGradientPaint(
				tileX + (spotlightX / 100f) * tileW, tileY + (spotlightY / 100f) * tileH,
				(spotlightSize / 100f) * max(tileW, tileH),
				floatArrayOf(0f, .4f),
				arrayOf(bgColorB.awtColor(), (fallOffColor and 0xFFFFFF).awtColor(true))
			)
		}
		ctx.fillRect(tileX.toInt(), tileY.toInt(), tileW.toInt(), tileH.toInt())*/

		// item image
		val itemImage = displayData.image
		if (itemImage != null) {
			if (w != h) {
				//println("itemImg w/h ${itemImage.width} ${itemImage.height}")
				val offsetImageX = p?.scalar?.get("OffsetImage_X") ?: 0f
				val offsetImageY = p?.scalar?.get("OffsetImage_Y") ?: 0f
				val zoomImagePct = p?.scalar?.get("ZoomImage_Percent") ?: 0f
				//println("ox=$offsetImageX oy=$offsetImageY zoomPct=$zoomImagePct")

				// centerCrop, needs more research
				val src = Rectangle(offsetImageX.toInt(), offsetImageY.toInt(), itemImage.width, itemImage.height)
				val deltaScale = artificialScale //zoomImagePct / 100f
				if (deltaScale != 0f) {
					//println("deltaScale $deltaScale")
					src.growByFac(-deltaScale)
				}
				val cropOffsetRatio = (1f - w / h) / 2f
				ctx.drawImage(itemImage,
					x.toInt(), y.toInt(),
					(x + w).toInt(), (y + h).toInt(),
					src.x + (cropOffsetRatio * src.width).toInt(), src.y,
					src.x + ((1f - cropOffsetRatio) * src.width).toInt(), src.y + src.height,
					Color.GRAY,
					null
				)
			} else { // simple square
				ctx.drawImage(itemImage, x.toInt(), y.toInt(), w.toInt(), h.toInt(), null)
			}
		}

		val path = Path2D.Float()
		drawRarityBorder(ctx, offer, path)
		drawTitleAndSubtitle(ctx, path)
		drawPrice(ctx, offer, path)
		drawViolator(ctx, offer, path)
		drawItemNumber(ctx)
	}

	private inline fun drawRarityBorder(ctx: Graphics2D, offer: CatalogEntryHolder, path: Path2D.Float) {
		val palette = displayData.palette
		if (palette != null && !offer.getMeta("HideRarityBorder").equals("true", true)) {
			ctx.color = palette.Color1.toColor()
			path.moveTo(x, y + h - 72)
			path.lineTo(x + w, y + h - 82)
			path.lineTo(x + w, y + h - 74)
			path.lineTo(x, y + h - 67)
			path.closePath()
			ctx.fill(path)
		}
	}

	private inline fun drawTitleAndSubtitle(ctx: Graphics2D, path: Path2D.Float) {
		val r = x + w
		val b = y + h
		ctx.color = 0x1E1E1E.awtColor()
		path.reset()
		path.moveTo(x, b - 67)
		path.lineTo(r, b - 74)
		path.lineTo(r, b)
		path.lineTo(x, b)
		path.closePath()
		ctx.fill(path)

		val titleText = displayData.title?.toUpperCase().orEmpty()
		val subtitleText = displayData.subtitle?.toUpperCase().orEmpty()

		var totalHeight = 0
		var titleFontSize = 20f
		var titleFont = ResourcesContext.burbankBigRegularBlack.deriveFont(Font.ITALIC, titleFontSize)
		var titleFm = ctx.getFontMetrics(titleFont)
		while (titleFm.stringWidth(titleText) > w) {
			titleFontSize--
			titleFont = ResourcesContext.burbankBigRegularBlack.deriveFont(Font.ITALIC, titleFontSize)
			titleFm = ctx.getFontMetrics(titleFont)
		}
		totalHeight += titleFm.height
		val subtitleFont = ResourcesContext.burbankSmallBlack.deriveFont(16f)
		val subtitleFm = ctx.getFontMetrics(subtitleFont)
		if (subtitleText.isNotEmpty()) {
			totalHeight += -titleFm.descent + subtitleFm.height
		}

		val contentTop = b - 67
		val contentHeight = 39
		var cur = contentTop + (contentHeight - totalHeight) / 2

		ctx.color = Color.WHITE
		ctx.font = titleFont
		ctx.drawString(titleText, x + (w - titleFm.stringWidth(titleText)) / 2, cur + titleFm.ascent)
		cur += titleFm.height - titleFm.descent

		if (subtitleText.isNotEmpty()) {
			ctx.color = 0x7A7A7A.awtColor()
			ctx.font = subtitleFont
			ctx.drawString(subtitleText, x + (w - subtitleFm.stringWidth(subtitleText)) / 2, cur + subtitleFm.ascent)
		}
	}

	private inline fun drawPrice(ctx: Graphics2D, offer: CatalogEntryHolder, path: Path2D.Float) {
		val r = x + w
		val b = y + h
		ctx.color = 0x0E0E0E.awtColor()
		path.reset()
		path.moveTo(x, b - 26)
		path.lineTo(r, b - 28)
		path.lineTo(r, b)
		path.lineTo(x, b)
		path.closePath()
		ctx.fill(path)

		offer.resolve()

		var offset = 0
		if (offer.price.currencyType == EStoreCurrencyType.MtxCurrency) {
			offset = 42
			val iconSize = 40
			val oldClip = ctx.clip
			val oldTransform = ctx.transform
			val oldComposite = ctx.composite
			val iconX = r - 4 - iconSize
			val iconY = b - iconSize + 7
			ctx.clip = path
			ctx.rotate(Math.toRadians(17.0), iconX + iconSize / 2.0, iconY + iconSize / 2.0)
			ctx.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .7f)
			ctx.drawImage(mtxIcon.toBufferedImage(), iconX.toInt(), iconY.toInt(), iconSize, iconSize, null)
			ctx.clip = oldClip
			ctx.transform = oldTransform
			ctx.composite = oldComposite
		}

		var priceNum = offer.price.basePrice
		var priceText = Formatters.num.format(priceNum)
		ctx.color = 0xA7B8BC.awtColor()
		ctx.font = ResourcesContext.burbankBigRegularBlack.deriveFont(Font.ITALIC, 16f)
		var cur = r - 8 - offset - ctx.fontMetrics.stringWidth(priceText)
		ctx.drawString(priceText, cur, b - 9)

		if (offer.price.regularPrice != offer.price.basePrice) {
			priceNum = offer.price.regularPrice
			priceText = Formatters.num.format(priceNum)
			ctx.color = 0x5AFFFFFF.awtColor()
			ctx.font = ResourcesContext.burbankBigRegularBlack.deriveFont(Font.ITALIC, 16f)
			val regPriceTextWidth = ctx.fontMetrics.stringWidth(priceText)
			cur -= 8 + regPriceTextWidth
			ctx.drawString(priceText, cur, b - 9)
			ctx.color = 0x99A7B8BC.awtColor()
			ctx.stroke = BasicStroke(2f)
			ctx.drawLine(cur.toInt() - 2, (b - 12).toInt(), cur.toInt() + regPriceTextWidth + 4, (b - 18).toInt())
		}
	}

	private inline fun drawViolator(ctx: Graphics2D, offer: CatalogEntryHolder, path: Path2D.Float) {
		val violatorIntensity = runCatching { EViolatorIntensity.valueOf(offer.getMeta("ViolatorIntensity")!!) }.getOrNull()
			?: EViolatorIntensity.Low
		var violatorText: String? = null
		if (offer.ce.offerType == ECatalogOfferType.DynamicBundle && offer.price.regularPrice != offer.price.finalPrice) {
			val discount = offer.price.regularPrice - offer.price.finalPrice
			violatorText = "%,d V-Bucks Off".format(discount)
		}
		val violatorTag = offer.getMeta("ViolatorTag")
		if (violatorTag != null) {
			violatorText = catalogMessages.Banners[violatorTag]?.format() ?: violatorTag
		}
		violatorText = (violatorText ?: return).toUpperCase()

		val palette = violatorPalettes[violatorIntensity]!!
		ctx.font = ResourcesContext.burbankBigRegularBlack.deriveFont(Font.ITALIC, 19f)
		val fm = ctx.fontMetrics
		val textWidth = fm.stringWidth(violatorText)

		//outline
		ctx.color = palette.outline.awtColor()
		path.reset()
		path.moveTo(x - 12, y - 9)
		path.lineTo(x + 22 + textWidth, y - 12)
		path.lineTo(x + 14 + textWidth, y + 27)
		path.lineTo(x - 8, y + 26)
		path.closePath()
		ctx.fill(path)
		val bounds = path.bounds

		//inside
		ctx.color = palette.inside.awtColor()
		path.reset()
		path.moveTo(x - 6, y - 4)
		path.lineTo(x + 15 + textWidth, y - 6)
		path.lineTo(x + 9 + textWidth, y + 22)
		path.lineTo(x - 3, y + 21)
		path.closePath()
		ctx.fill(path)

		//text
		ctx.color = palette.text.awtColor()
		ctx.drawString(violatorText, bounds.x + (bounds.width - textWidth) / 2 - 2, bounds.y + ((bounds.height - fm.height) / 2) + fm.ascent)
	}

	private inline fun drawItemNumber(ctx: Graphics2D) {
		val itemNumber = this.offer.__ak47_index
		if (itemNumber != -1) {
			ctx.color = 0xB3FFFFFF.awtColor()
			ctx.font = ResourcesContext.burbankBigRegularBlack.deriveFont(Font.ITALIC, 20f)
			ctx.drawString((itemNumber + 1).toString(), x + 4, y + h - 6)
		}
	}
}

enum class EViolatorIntensity {
	Low,
	Medium,
	High
}

class FOffsets(val emoteX: Int, val gliderX: Int)

fun EItemShopTileSize.offsets() = when (this) {
	EItemShopTileSize.Mini -> FOffsets(0, 0)
	EItemShopTileSize.Small -> FOffsets(43, 15)
	EItemShopTileSize.Normal -> FOffsets(30, 0)
	EItemShopTileSize.DoubleWide -> FOffsets(0, 0)
	EItemShopTileSize.TripleWide -> FOffsets(0, 0)
}

class FMergedMaterialParams(material: UMaterialInstance) {
	val scalar = mutableMapOf<String, Float>()
	val vector = mutableMapOf<String, Int>()
	val texture = mutableMapOf<String, Lazy<UTexture>>()

	init {
		var cur: UMaterialInstance? = material
		while (cur != null) {
			cur.ScalarParameterValues?.forEach {
				scalar.putIfAbsent(it.ParameterInfo.Name.text, it.ParameterValue)
			}
			cur.VectorParameterValues?.forEach {
				vector.putIfAbsent(it.ParameterInfo.Name.text, it.ParameterValue.toFColor(true).toPackedARGB())
			}
			cur.TextureParameterValues?.forEach {
				texture.putIfAbsent(it.ParameterInfo.Name.text, it.ParameterValue)
			}
			cur = cur.Parent.value as? UMaterialInstance
		}
	}
}

fun Rectangle.growByFac(fac: Float) {
	val newWidth = (width + width * fac).toInt()
	val newHeight = (width + height * fac).toInt()
	setLocation(x + (width - newWidth) / 2, y + (height - newHeight) / 2)
	setSize(newWidth, newHeight)
}