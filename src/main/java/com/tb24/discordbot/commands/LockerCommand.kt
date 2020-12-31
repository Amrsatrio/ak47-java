package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.getPreviewImagePath
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.entities.Message
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.io.File
import java.util.*
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.sqrt

class LockerCommand : BrigadierCommand("locker", "kek") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		//.requires(Rune::isBotDevOrPotato)
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting BR game data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val ctgs: Map<String, MutableSet<FortItemStack>> = mapOf(
				"AthenaCharacter" to mutableSetOf(),
				"AthenaBackpack" to mutableSetOf(),
				"AthenaPickaxe" to mutableSetOf(),
				"AthenaGlider" to mutableSetOf(),
				"AthenaSkyDiveContrail" to mutableSetOf(),
				"AthenaDance" to mutableSetOf(),
				"AthenaItemWrap" to mutableSetOf(),
				"AthenaMusicPack" to mutableSetOf(),
				"AthenaLoadingScreen" to mutableSetOf()
			)
			for (item in athena.items.values) {
				val ids = ctgs[item.primaryAssetType]
				if (ids != null && (item.primaryAssetType != "AthenaDance" || item.primaryAssetName.startsWith("eid_"))) {
					ids.add(item)
				}
			}

			fun perform(name: String, ids: Collection<FortItemStack>?) = CompletableFuture.supplyAsync {
				if (ids == null || ids.isEmpty()) {
					return@supplyAsync null
				}
				val slots = mutableListOf<GridSlot>()
				for (item in ids.sortedWith(SimpleAthenaLockerItemComparator())) {
					val itemData = item.defData ?: return@supplyAsync null
					slots.add(GridSlot(
						image = item.getPreviewImagePath()?.load<UTexture2D>()?.toBufferedImage(),
						name = item.displayName,
						rarity = itemData.Rarity
					))
				}
				var png: ByteArray
				var scale = 1f
				do {
					png = createAttachmentOfIcons(slots, "locker", scale)
					//println("png size ${png.size} scale $scale")
					scale -= 0.2f
				} while (png.size > Message.MAX_FILE_SIZE && scale >= 0.2f)
				source.channel.sendMessage("**$name** (${Formatters.num.format(ids.size)})")
					.addFile(png, "$name-${source.api.currentLoggedIn.id}.png").complete()
			}

			source.loading("Generating and uploading images")
			CompletableFuture.allOf(
				perform("Outfits", ctgs["AthenaCharacter"]),
				perform("Back Blings", ctgs["AthenaBackpack"]),
				perform("Harvesting Tools", ctgs["AthenaPickaxe"]),
				perform("Gliders", ctgs["AthenaGlider"]),
				perform("Contrails", ctgs["AthenaSkyDiveContrail"]),
				perform("Emotes", ctgs["AthenaDance"]),
				perform("Wraps", ctgs["AthenaItemWrap"]),
				perform("Musics", ctgs["AthenaMusicPack"]),
				perform("Loading Screens", ctgs["AthenaLoadingScreen"])
			).await()
			source.complete("✅ All images have been sent successfully.")
			Command.SINGLE_SUCCESS
		}

	fun createAttachmentOfIcons(slots: List<GridSlot>, type: String, scale: Float = 1f): ByteArray {
		val COLUMNS = ceil(sqrt(slots.size.toDouble())).toInt()
		val tileSize = (200 * scale).toInt()
		return createAndDrawCanvas(COLUMNS * tileSize, ceil(slots.size.toDouble() / COLUMNS.toDouble()).toInt() * tileSize) { ctx ->
			ctx.font = ResourcesContext.burbankBigCondensedBlack.deriveFont(Font.PLAIN, 25f * scale)
			val bgImg = ImageIO.read(File("./canvas/base.png"))

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

				if (slot.rarity != null && File("./canvas/${slot.rarity.name.toLowerCase()}.png").exists()) {
					ctx.drawImage(ImageIO.read(File("./canvas/${slot.rarity.name.toLowerCase()}.png")), x, y, tileSize, tileSize, null)
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
}