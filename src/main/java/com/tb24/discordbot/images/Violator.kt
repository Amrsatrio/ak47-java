package com.tb24.discordbot.images

import com.tb24.discordbot.util.ResourcesContext
import com.tb24.discordbot.util.awtColor
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Path2D

enum class EViolatorIntensity {
	Low,
	Medium,
	High
}

private val violatorPalettes = mapOf(
	EViolatorIntensity.Low to FViolatorColorPalette(0xFF2C78, 0xCF0067, 0xFFFFFF),
	EViolatorIntensity.Medium to FViolatorColorPalette(0xFF2C78, 0xCF0067, 0xFFFFFF), // TODO test medium
	EViolatorIntensity.High to FViolatorColorPalette(0xFFFFFF, 0xFFFF00, 0x00062B),
)

fun drawViolator(ctx: Graphics2D, x: Float, y: Float, violatorIntensity: EViolatorIntensity, violatorText: String, path: Path2D.Float) {
	val violatorText = violatorText.toUpperCase()
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