package com.tb24.discordbot

import com.tb24.discordbot.util.createAndDrawCanvas
import com.tb24.discordbot.util.exec
import me.fungames.jfortniteparse.ue4.objects.core.math.FVector2D
import okhttp3.Request
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class MapImageGenerator(var background: BufferedImage?) {
	companion object {
		val watermark by lazy {
			val client = DiscordBot.instance
			val avatarUrl = client.discord.selfUser.avatarUrl
				?: return@lazy null
			client.okHttpClient.newCall(Request.Builder().url(avatarUrl).build()).exec().body()!!.byteStream().use { ImageIO.read(it) }
		}
	}

	var worldRadius = 135000
	var w = 2048
	var h = 2048
	var markers = mutableListOf<MapMarker>()

	fun draw() = createAndDrawCanvas(w, h) { ctx ->
		background?.let { ctx.drawImage(background, 0, 0, w, h, null) }
		markers.forEach {
			val mx = ((it.position.y + worldRadius) / (worldRadius * 2)) * w
			val my = (1 - (it.position.x + worldRadius) / (worldRadius * 2)) * h
			it.draw(ctx, mx.toInt(), my.toInt())
		}
		DiscordBot.instance.discord.selfUser.avatarUrl?.let {
			val pad = 32
			val sz = 384
			val originalComposite = ctx.composite
			ctx.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .5f)
			ctx.drawImage(watermark, pad, h - pad - sz, sz, sz, null)
			ctx.composite = originalComposite
		}
	}

	class MapMarker(val position: FVector2D, val draw: (ctx: Graphics2D, mx: Int, my: Int) -> Unit)
}