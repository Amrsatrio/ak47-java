package com.tb24.discordbot.images

import com.tb24.discordbot.util.ResourcesContext
import com.tb24.discordbot.util.awtColor
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

fun Graphics2D.drawHeader(icon: BufferedImage?, title: String, subtitle: String, x: Int, y: Int, scale: Float = 1f) {
	val primaryColor = 0xB3B3B3.awtColor() //0x3DE5F3.awtColor()

	// Icon
	if (icon != null) {
		color = Color.BLACK
		//fillRect(x, y, (128 * scale).toInt(), (128 * scale).toInt())
		drawImage(icon, x, y, (128 * scale).toInt(), (128 * scale).toInt(), null)
	}

	// Separator
	val path = Path2D.Float()
	val oldTransform = transform
	translate(x + (128 * scale).toInt(), y + (8 * scale).toInt())
	path.angledRect(36f * scale, 112f * scale, ltx = 0.65f, rty = 0.05f, rbx = 0.85f, lby = 0.06f)
	color = primaryColor
	fill(path)
	transform = oldTransform

	// Title
	font = ResourcesContext.burbankBigRegularBlack.deriveFont(Font.ITALIC, 72f * scale)
	color = Color.WHITE
	val titleDisplay = title.toUpperCase()
	drawString(titleDisplay, x + (128 + 44) * scale, y + 66 * scale)

	// Subtitle
	font = ResourcesContext.burbankBigRegularBlack.deriveFont(Font.ITALIC, 40 * scale)
	color = primaryColor
	val subtitleDisplay = subtitle.toUpperCase()
	drawString(subtitleDisplay, x + (128 + 30) * scale, y + 112 * scale)
}

fun Path2D.Float.angledRect(
	w: Float,
	h: Float,
	ltx: Float = 0f,
	lty: Float = 0f,
	rtx: Float = 0f,
	rty: Float = 0f,
	rbx: Float = 0f,
	rby: Float = 0f,
	lbx: Float = 0f,
	lby: Float = 0f,
	frac: Boolean = true
): Path2D.Float {
	val ltx = if (frac) ltx * w else ltx
	val lty = if (frac) lty * h else lty
	val rtx = if (frac) rtx * w else rtx
	val rty = if (frac) rty * h else rty
	val rbx = if (frac) rbx * w else rbx
	val rby = if (frac) rby * h else rby
	val lbx = if (frac) lbx * w else lbx
	val lby = if (frac) lby * h else lby

	reset()
	moveTo(ltx, lty)
	lineTo(w - rtx, rty)
	lineTo(w - rbx, h - rby)
	lineTo(lbx, h - lby)
	closePath()

	return this
}