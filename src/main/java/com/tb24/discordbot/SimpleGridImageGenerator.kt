package com.tb24.discordbot

import com.tb24.discordbot.util.ResourcesContext
import com.tb24.discordbot.util.createAndDrawCanvas
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.util.toPngArray
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.sqrt

fun createAttachmentOfIcons(slots: List<GridSlot>, type: String, scale: Float = 1f): ByteArray {
	val COLUMNS = ceil(sqrt(slots.size.toDouble())).toInt()
	val tileSize = (200 * scale).toInt()
	return createAndDrawCanvas(COLUMNS * tileSize, ceil(slots.size.toDouble() / COLUMNS.toDouble()).toInt() * tileSize) { ctx ->
		ctx.font = ResourcesContext.burbankBigCondensedBlack.deriveFont(Font.PLAIN, 25f * scale)
		val bgImg = ImageIO.read(File("canvas/base.png"))

		for (i in slots.indices) {
			val slot = slots[i]

			val x = i % COLUMNS * tileSize
			val y = i / COLUMNS * tileSize

			if (type == "shop") { // draw background if it's for item shop, we need to reduce the image size for locker
				ctx.drawImage(bgImg, x, y, tileSize, tileSize, null)
			}

			if (slot.image != null) {
				ctx.drawImage(slot.image, x, y, tileSize, tileSize, null)
			} else if (slot.url != null) {
				TODO() //ctx.drawImage(ImageIO.read(File(slot.url)), x, y, TILE_SIZE, TILE_SIZE, null) // icon
			}

			if (slot.rarity != null && File("canvas/${slot.rarity.name.toLowerCase()}.png").exists()) {
				ctx.drawImage(ImageIO.read(File("canvas/${slot.rarity.name.toLowerCase()}.png")), x, y, tileSize, tileSize, null)
			}

			if (slot.name != null) {
				val text = if (type == "shop") "(${(slot.index ?: i) + 1}) ${slot.name}" else slot.name
				val textDimen = TextLayout(text, ctx.font, ctx.fontRenderContext)
				val shape = textDimen.getOutline(null)
				val hpad = (10 * scale).toInt()
				val tx = x + hpad
				val ty = y + tileSize - (6 * scale).toInt()
				ctx.translate(tx, ty)
				ctx.stroke = BasicStroke(6f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
				ctx.color = Color.BLACK
				ctx.draw(shape)
				ctx.color = Color.WHITE
				ctx.fill(shape)
				ctx.translate(-tx, -ty)
			}
		}
	}.toPngArray()
}

class GridSlot(
	val image: BufferedImage? = null,
	val url: String? = null,
	val name: String? = null,
	val rarity: EFortRarity? = null,
	val index: Int? = null
)