package com.tb24.discordbot.images

import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.util.createAndDrawCanvas
import com.tb24.discordbot.util.exec
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.ue4.objects.core.math.FVector2D
import okhttp3.Request
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.Shape
import java.awt.geom.GeneralPath
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class MapImageGenerator(var background: BufferedImage?, var backgroundMask: BufferedImage?) {
	companion object {
		val watermark by lazy {
			val client = DiscordBot.instance
			val avatarUrl = client.discord.selfUser.avatarUrl
				?: return@lazy null
			client.okHttpClient.newCall(Request.Builder().url(avatarUrl).build()).exec().body()!!.byteStream().use(ImageIO::read)
		}
	}

	var worldRadius = 135000
	var w = 2048
	var h = 2048
	var markers = mutableListOf<MapMarker>()
	var paths = mutableListOf<MapPath>()

	constructor() : this(
		loadObject<UTexture2D>("/Game/Athena/Apollo/Maps/UI/Apollo_Terrain_Minimap.Apollo_Terrain_Minimap")?.toBufferedImage(),
		null //loadObject<UTexture2D>("/Game/Athena/Apollo/Maps/UI/T_MiniMap_Mask.T_MiniMap_Mask")?.toBufferedImage() +1mb size, not worth it
	)

	fun draw() = createAndDrawCanvas(w, h) { ctx ->
		background?.let {
			if (backgroundMask != null) {
				val originalComposite = ctx.composite
				ctx.drawImage(backgroundMask, 0, 0, w, h, null)
				ctx.composite = AlphaComposite.SrcIn
				ctx.drawImage(background, 0, 0, w, h, null)
				ctx.composite = originalComposite
			} else {
				ctx.drawImage(background, 0, 0, w, h, null)
			}
		}
		paths.forEach {
			it.preDraw(ctx)
			val path = it.getPath(this)
			if (it.style == MapPath.EPathStyle.Stroke) {
				ctx.draw(path)
			} else {
				ctx.fill(path)
			}
			it.postDraw(ctx)
		}
		markers.forEach {
			val vec = it.position
			val (mx, my) = mapToImagePos(vec)
			it.draw(ctx, mx.toInt(), my.toInt())
		}
		DiscordBot.getInstanceOrNull()?.discord?.selfUser?.avatarUrl?.let {
			val pad = 32
			val sz = 384
			val originalComposite = ctx.composite
			ctx.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .5f)
			ctx.drawImage(watermark, pad, h - pad - sz, sz, sz, null)
			ctx.composite = originalComposite
		}
	}

	fun mapToImagePos(vec: FVector2D): FVector2D {
		val mx = ((vec.y + worldRadius) / (worldRadius * 2)) * w
		val my = (1 - (vec.x + worldRadius) / (worldRadius * 2)) * h
		return FVector2D(mx, my)
	}

	class MapMarker(val position: FVector2D, val draw: (ctx: Graphics2D, mx: Int, my: Int) -> Unit)

	open class MapPath(val style: EPathStyle) {
		val ops = mutableListOf<Op>()

		fun getPath(map: MapImageGenerator): Shape {
			val path = GeneralPath()
			for (op in ops) {
				when (op.opcode) {
					EPathOp.Move -> {
						val (x, y) = map.mapToImagePos(op.data as FVector2D)
						path.moveTo(x, y)
					}
					EPathOp.Line -> {
						val (x, y) = map.mapToImagePos(op.data as FVector2D)
						path.lineTo(x, y)
					}
					EPathOp.Close -> path.closePath()
				}
			}
			return path
		}

		open fun preDraw(ctx: Graphics2D) {}
		open fun postDraw(ctx: Graphics2D) {}

		class Op(val opcode: EPathOp, val data: Any? = null)

		enum class EPathOp {
			Move,
			Line,
			Close
		}

		enum class EPathStyle {
			Stroke,
			Fill
		}
	}
}

private inline operator fun FVector2D.component1() = x
private inline operator fun FVector2D.component2() = y