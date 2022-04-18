package com.tb24.discordbot.images

import com.google.gson.JsonParser
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.commands.rarityData
import com.tb24.discordbot.item.ExclusivesType
import com.tb24.discordbot.item.exclusives
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.fn.util.getString
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.entities.User
import okhttp3.OkHttpClient
import java.awt.*
import java.awt.font.TextLayout
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileReader
import java.util.concurrent.CompletableFuture
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.system.exitProcess

fun main() {
	val api = EpicApi(OkHttpClient())
	fun loadProfile(s: String) {
		FileReader("D:\\Downloads\\ComposeMCP\\$s.json").use {
			val d = JsonParser.parseReader(it).asJsonObject
			api.profileManager.localProfileGroup.profileData[d.getString("profileId")] = EpicApi.GSON.fromJson(d, McpProfile::class.java)
		}
	}
	AssetManager.INSTANCE.loadPaks()
	loadProfile("ComposeMCP-amrsatrio-queryprofile-athena-26541")
	val athena = api.profileManager.getProfileData("athena")
	athena.owner = GameProfile("11223344556677889900aabbccddeeff", "amrsatrio")
	val items = athena.items.values.filter { it.primaryAssetType == "AthenaCharacter" }
	if (items.isEmpty()) {
		throw SimpleCommandExceptionType(LiteralMessage("Nothing here")).create()
	}
	File("locker.png").writeBytes(generateLockerImage(items, GenerateLockerImageParams("Outfits", "/Game/UI/Foundation/Textures/Icons/Locker/T_Ui_Outfit_256.T_Ui_Outfit_256").apply { epicUser = athena.owner }).toPngArray())
	exitProcess(0)
}

const val EXCLUSIVES_NONE = 0
const val EXCLUSIVES_INFO = 1
const val EXCLUSIVES_DISTINGUISH = 2

class GenerateLockerImageParams(
	val name: String? = null,
	val icon: String? = null,
	val withAlpha: Boolean = true,
	val showUser: Boolean = true,
	val exclusives: Int = EXCLUSIVES_NONE,
) {
	var epicUser: GameProfile? = null
	var discordUser: User? = null

	fun withSource(source: CommandSourceStack): GenerateLockerImageParams {
		if (showUser) {
			epicUser = source.api.currentLoggedIn
			discordUser = source.author
		}
		return this
	}
}

fun generateLockerImage(items: List<FortItemStack>, params: GenerateLockerImageParams): BufferedImage {
	val items = items.sortedWith(SimpleAthenaLockerItemComparator().apply { prioritizeFavorites = false })
	val name = params.name
	val icon = params.icon
	val epicUser = params.epicUser
	val discordUser = params.discordUser
	val withAlpha = params.withAlpha
	val exclusivesOption = params.exclusives

	// Preload icons
	val icons = hashMapOf<String, BufferedImage?>()
	CompletableFuture.allOf(*items.map {
		CompletableFuture.supplyAsync {
			icons[it.templateId] = it.getPreviewImagePath()?.load<UTexture2D>()?.toBufferedImage()
		}
	}.toTypedArray()).get()

	val columns = ceil(sqrt(items.size.toDouble())).toInt()
	val padding = 6
	val doublePadding = 2 * padding
	val tileSize = 200 + doublePadding
	val imageW = columns * tileSize + doublePadding
	val headerScale = imageW / 1600f
	val topContentHeight = (headerScale * 128).toInt()
	val top = doublePadding + topContentHeight
	val imageH = top + ceil(items.size.toDouble() / columns.toDouble()).toInt() * tileSize + doublePadding
	val image = createAndDrawCanvas(imageW, imageH, withAlpha) { ctx ->
		// Background
		ctx.color = 0x161616.awtColor()
		ctx.fillRect(0, 0, imageW, imageH)

		// Header
		val icon = icon?.let { loadObject<UTexture2D>(it) }?.toBufferedImage()
		ctx.drawHeader(icon, "Locker", name ?: "", doublePadding, doublePadding, headerScale)

		// Owner
		if (epicUser != null) {
			ctx.font = ResourcesContext.burbankSmallBlack.deriveFont(48f * headerScale)
			val ownerEpicText = epicUser.displayName
			val ownerEpicTextWidth = ctx.fontMetrics.stringWidth(ownerEpicText)
			ctx.color = 0xB3B3B3.awtColor() //ResourcesContext.primaryColor.awtColor()
			ctx.drawString(ownerEpicText, imageW - doublePadding - ownerEpicTextWidth, doublePadding + ctx.fontMetrics.ascent + ctx.fontMetrics.height / 2)
			val epicGamesLogo = loadObject<UTexture2D>("/Game/UI/Foundation/Textures/Icons/Social/T-Icon-EpicGamesLogo-64.T-Icon-EpicGamesLogo-64")?.toBufferedImage()
			val logoSize = (64 * headerScale).toInt()
			val logoPadding = (8 * headerScale).toInt()
			ctx.drawImage(epicGamesLogo, imageW - doublePadding - ownerEpicTextWidth - logoPadding - logoSize, doublePadding + (topContentHeight - logoSize) / 2, logoSize, logoSize, null)
		}
		// TODO Show Discord user

		// Contents
		val path = Path2D.Float()

		for (i in items.indices) {
			val item = items[i]
			val exclusiveInfo = if (exclusivesOption != EXCLUSIVES_NONE) {
				exclusives[item.templateId.toLowerCase()]
			} else null

			val x = doublePadding + (i % columns * tileSize)
			val y = top + doublePadding + (i / columns * tileSize)
			val w = tileSize - padding * 2
			val h = tileSize - padding * 2

			// Background
			val palette = if (exclusivesOption == EXCLUSIVES_DISTINGUISH && exclusiveInfo?.type == ExclusivesType.EXCLUSIVE) rarityData.forRarity(EFortRarity.Mythic) else item.palette
			ctx.drawStretchedRadialGradient(palette.Color2.toFColor(true).toPackedARGB(), palette.Color3.toFColor(true).toPackedARGB(), x, y, w, h)

			// Icon
			icons[item.templateId]?.let {
				ctx.drawImage(it, x, y, w, h, null)
			}

			// Rarity overlay
			val rarityGrad = LinearGradientPaint(0f, 0f, w.toFloat(), 0f, floatArrayOf(0f, 1f), arrayOf(palette.Color1.toColor(), palette.Color2.toColor()))
			ctx.translate(x, y)

			// - Background
			ctx.paint = rarityGrad
			val oldComposite = ctx.composite
			ctx.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f)
			val lo = h / 12f
			val ro = h / 7f
			path.reset()
			path.moveTo(0f, h - lo)
			path.lineTo(w.toFloat(), h - ro)
			path.lineTo(w.toFloat(), h.toFloat())
			path.lineTo(0f, h.toFloat())
			path.closePath()
			ctx.fill(path)
			ctx.composite = oldComposite

			// - Foreground
			val thicknessLeft = h / 25f //20f
			val thicknessRight = h / 25f //20f
			path.reset()
			path.moveTo(0f, h - lo)
			path.lineTo(w.toFloat(), h - ro)
			path.lineTo(w.toFloat(), h - ro + thicknessRight)
			path.lineTo(0f, h - lo + thicknessLeft)
			path.closePath()
			ctx.fill(path)

			ctx.translate(-x, -y)

			// Item display name
			val name = item.displayName
			if (!name.isNullOrEmpty()) {
				val text = name.toUpperCase()
				val hpad = 10
				var fontSize = 25f
				ctx.font = ResourcesContext.burbankBigCondensedBlack.deriveFont(Font.PLAIN, fontSize)
				var fm = ctx.fontMetrics
				while (fm.stringWidth(text) > (w - hpad * 2)) {
					fontSize--
					ctx.font = ctx.font.deriveFont(Font.PLAIN, fontSize)
					fm = ctx.fontMetrics
				}
				val textDimen = TextLayout(text, ctx.font, ctx.fontRenderContext)
				val shape = textDimen.getOutline(null)
				val tx = x + hpad
				val ty = y + h - 6
				ctx.translate(tx, ty)
				ctx.stroke = BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
				ctx.color = Color.BLACK
				ctx.draw(shape)
				ctx.color = Color.WHITE
				ctx.fill(shape)
				ctx.translate(-tx, -ty)
			}

			if (exclusiveInfo != null) {
				val oldTransform = ctx.transform
				ctx.translate(x, y)
				ctx.scale(0.75, 0.75)
				drawViolator(ctx, 0f, 0f, if (exclusiveInfo.type == ExclusivesType.UNIQUE) EViolatorIntensity.Low else EViolatorIntensity.High, exclusiveInfo.reason, path)
				ctx.transform = oldTransform
			}
		}
	}
	return image
}