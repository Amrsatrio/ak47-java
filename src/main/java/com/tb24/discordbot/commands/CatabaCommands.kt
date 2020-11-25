package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.EItemShopTileSize
import com.tb24.fn.model.EItemShopTileSize.*
import com.tb24.fn.model.FortCatalogResponse
import com.tb24.fn.model.FortCmsData
import com.tb24.fn.model.FortCmsData.ShopSection
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.uasset.AssetManager
import me.fungames.jfortniteparse.fileprovider.FileProvider
import me.fungames.jfortniteparse.fort.FortResources
import okhttp3.Request
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileReader
import java.time.Instant
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.JComponent
import kotlin.system.exitProcess

class CatabaCommand : BrigadierCommand("cataba", "New shop") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting the shop")
			val catalogManager = source.client.catalogManager
			val profileManager = source.api.profileManager
			catalogManager.ensureCatalogData(source.api)
			CompletableFuture.allOf(
				profileManager.dispatchClientCommandRequest(QueryProfile()),
				profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
			).await()
			source.loading("Getting additional data")
			val cmsData = source.api.okHttpClient.newCall(Request.Builder().url("https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game").build()).exec().to<FortCmsData>()
			val sections = cmsData.shopSections.sectionList.sections.associate {
				val section = CatabaSection(it)
				section.cmsBacking.sectionId to section
			}
			for (storefront in catalogManager.catalogData!!.storefronts) {
				for (catalogEntry in storefront.catalogEntries) {
					val ce = catalogEntry.holder()
					(sections[ce.getMeta("SectionId") ?: continue] ?: continue).items.add(ce)
				}
			}
			val embed = source.createEmbed()
				.setColor(0x0099FF)
				.setTitle("☂ " + "Battle Royale Item Shop")
				.setTimestamp(Instant.now())
			if (source.session.id != "__internal__") {
				embed.setDescription("Use `${source.prefix}buy` or `${source.prefix}gift` to perform operations with these items.\n✅ = Owned")
				//.addField(if (prices.size == 1) "Balance" else "Balances", prices.values.joinToString(" \u00b7 ") { it.getAccountBalanceText(profileManager) }, false)
			}
			for (section in sections.values) {
				if (section.items.isEmpty()) {
					continue
				}
				embed.addFieldSeparate(section.cmsBacking.sectionDisplayName ?: "", section.items, 0) {
					it.resolve(source.api.profileManager)
					"${(it.ce.__ak47_index + 1)}. ${it.friendlyName}${if (it.owned) " ✅" else ""}"
				}
			}
			source.complete(null, embed.build())
			Command.SINGLE_SUCCESS
		}
}

class CatabaSection(val cmsBacking: ShopSection) {
	val items = mutableListOf<CatalogEntryHolder>()
}

fun main() {
//	AssetManager.INSTANCE.loadPaks()
	val img = CatabaImageGenerator(
		EpicApi.GSON.fromJson(FileReader("D:\\Downloads\\shop-17-10-2020-en.json"), FortCatalogResponse::class.java),
		EpicApi.GSON.fromJson(FileReader("C:\\Users\\satri\\Downloads\\cms17-10-2020.json"), FortCmsData::class.java),
		AssetManager.INSTANCE.provider
	).draw()
	val file = File("test.png")
	println(file.absolutePath)
	ImageIO.write(img, "png", file)
	exitProcess(0)
}

const val CATABA_SECTION_ENTRY_WIDTH = 375
const val CATABA_SECTION_ENTRY_HEIGHT = 648
const val CATABA_SECTION_ENTRY_GAP = 28

class CatabaImageGenerator(val data: FortCatalogResponse, val cmsData: FortCmsData, val provider: FileProvider) {
//	val burbankBigRgBk by lazy { Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(provider.saveGameFile("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigRegular-Black.ufont"))) }
//	val burbankBigCdBk by lazy { Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(provider.saveGameFile("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigCondensed-Black.ufont"))) }

	val sections = cmsData.shopSections.sectionList.sections.associate {
		val section = CatabaSection(it)
		section.cmsBacking.sectionId to section
	}

	init {
		for (storefront in data.storefronts) {
			for (catalogEntry in storefront.catalogEntries) {
				val ce = catalogEntry.holder()
				(sections[ce.getMeta("SectionId") ?: continue] ?: continue).items.add(ce)
			}
		}
	}

	fun draw(): BufferedImage {
		val w = 1024
		val h = 1024
		val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
		img.createGraphics().apply {
			setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
			setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
			drawBackground(img)
			font = FortResources.burbank.deriveFont(Font.PLAIN, 72f)
			color = 0xFFFFFFFF.awtColor()
			val title = "Item Shop".toUpperCase()
			drawString(title, (img.width - fontMetrics.stringWidth(title)) / 2, fontMetrics.height)
			var c = fontMetrics.height + 10
			for (section in sections.values) {
				CatabaSectionContainer(section).draw(this, 60, c)
				c += CATABA_SECTION_ENTRY_HEIGHT + CATABA_SECTION_ENTRY_GAP
			}
		}
		return img
	}

	private fun Graphics2D.drawBackground(img: BufferedImage) {
		val fac = .3f
		paint = RadialGradientPaint(
			Rectangle(img.width, img.height).apply { grow((img.width * fac).toInt(), (img.height * fac).toInt()) },
			floatArrayOf(0f, 1f),
			arrayOf(0xFF099AFE.awtColor(), 0xFF0942B4.awtColor()),
			MultipleGradientPaint.CycleMethod.NO_CYCLE)
		fillRect(0, 0, img.width, img.height)
	}
}

class CatabaSectionContainer(val section: CatabaSection) {
	val entries = section.items.map { CatabaCatalogEntryContainer(it) }
	val container = Container()

	init {
		container.preferredSize = Dimension(2048, CATABA_SECTION_ENTRY_HEIGHT)
		container.layout = GridLayout(2, 2, CATABA_SECTION_ENTRY_GAP, CATABA_SECTION_ENTRY_GAP)
		entries.forEach(container::add)
		container.doLayout()
	}

	/*init {
		val size = 2
		var cx = 0
		var cy = 0
		var columnSize = 1
		entries.forEach {
			val sx = it.sx
			val sy = it.sy
			if (cy + sy > size) { // has enough Y space? if not then increment X

				cy = 0
			}
			it.gridX = cx
			it.gridY = cy
			cy += sy
			if (cy >= size) {
				cx += sx
				cy += sy
			}
		}
	}*/

	fun draw(g: Graphics2D, x: Int, y: Int) {
		g.apply {
			font = FortResources.burbank.deriveFont(Font.PLAIN, 36f)
			color = 0xFFFFFFFF.awtColor()
			drawString(section.cmsBacking.sectionDisplayName?.toUpperCase() ?: section.toString(), x, y + 36)
			/*val cx = IntRef().apply { element = x }
			entries.forEach {
				it.draw(g, cx, IntRef().apply { element = y + 36 + 36 })
			}*/
			container.paint(this)
			container.components.firstOrNull()?.paint(this)
		}
	}
}

class CatabaCatalogEntryContainer(
	val catalogEntry: CatalogEntryHolder,
	val size: EItemShopTileSize = valueOf(catalogEntry.getMeta("TileSize") ?: "Normal")
) : JComponent() {
	var gridX = 0
	var gridY = 0

	val sx = when (size) {
		Small, Normal -> CATABA_SECTION_ENTRY_WIDTH
		DoubleWide -> 2 * CATABA_SECTION_ENTRY_WIDTH + CATABA_SECTION_ENTRY_GAP
	}
	val sy = when (size) {
		Small -> (CATABA_SECTION_ENTRY_HEIGHT - CATABA_SECTION_ENTRY_GAP) / 2
		Normal, DoubleWide -> CATABA_SECTION_ENTRY_HEIGHT
	}
	val spanX = when (size) {
		Small, Normal -> 1
		DoubleWide -> 2
	}
	val spanY = when (size) {
		Small -> 1
		Normal, DoubleWide -> 2
	}

	override fun paint(g: Graphics?) {
		super.paint(g)
		g!!.apply {
			color = 0xFF1E1E1E.awtColor()
			fillRect(x, y, width, height)
		}
	}

	override fun getPreferredSize() = Dimension(sx, sy)
}