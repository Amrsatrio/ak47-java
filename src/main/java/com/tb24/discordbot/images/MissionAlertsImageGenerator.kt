package com.tb24.discordbot.images

import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.commands.iterateMissions
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.assetdata.FortActiveTheaterInfo
import com.tb24.fn.model.assetdata.FortMissionGenerator
import com.tb24.fn.model.assetdata.FortTheaterInfo
import com.tb24.fn.model.assetdata.FortZoneTheme
import com.tb24.fn.network.AccountService
import com.tb24.fn.util.EAuthClient
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.AssetManager
import com.tb24.uasset.StructJson
import com.tb24.uasset.loadCDO
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.fort.objects.rows.GameDifficultyInfo
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.util.drawCenteredString
import me.fungames.jfortniteparse.util.toPngArray
import okhttp3.OkHttpClient
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

fun main() {
	AssetManager.INSTANCE.loadPaks()
	val api = EpicApi(OkHttpClient())
	api.setToken(api.accountService.getAccessToken(EAuthClient.FORTNITE_PC_GAME_CLIENT.asBasicAuthString(), AccountService.GrantType.clientCredentials(), null, null).exec().body()!!)
	val response = api.fortniteService.queryTheaterList("en").exec().body()!!.charStream()
		.use { StructJson.GSON.fromJson(it, FortActiveTheaterInfo::class.java) }
	File("alerts.png").writeBytes(generateMissionAlertsImage(response).toPngArray())
	api.accountService.killSession(api.userToken.access_token)
	exitProcess(0)
}

private fun generateMissionAlertsImage(resp: FortActiveTheaterInfo): BufferedImage {
	val stonewood = mutableListOf<MissionHolder>()
	val plankerton = mutableListOf<MissionHolder>()
	val cannyvalley = mutableListOf<MissionHolder>()
	val twinepeaks = mutableListOf<MissionHolder>()
	resp.iterateMissions { theater, mission, missionAlert ->
		val list = when (theater.UniqueId) {
			"33A2311D4AE64B361CCE27BC9F313C8B" -> stonewood
			"D477605B4FA48648107B649CE97FCF27" -> plankerton
			"E6ECBD064B153234656CB4BDE6743870" -> cannyvalley
			"D9A801C5444D1C74D1B7DAB5C7C12C5B" -> twinepeaks
			else -> return@iterateMissions true
		}
		if (missionAlert != null) {
			list.add(MissionHolder(theater, mission, missionAlert))
		}
		true
	}
	val height = maxOf(stonewood.size, plankerton.size, cannyvalley.size, twinepeaks.size)
	val imageW = 1920
	val imageH = 30 * height + 295
	return createAndDrawCanvas(imageW, imageH) { ctx ->
		// Missions
		//val Power = ImageIO.read(File("assets/images/missions/Power.png"))

		// Info
		//val watermark = ImageIO.read(File("assets/images/watermarks/Fortniters.png"))
		//val Time = ImageIO.read(File("assets/images/time.png"))
		//val Twitter = ImageIO.read(File("assets/images/watermarks/Twitter.png"))
		//val Fortnite = ImageIO.read(File("assets/images/watermarks/Fortnite.png"))
		//val VBuck1 = ImageIO.read(File("assets/images/vBuck1.png"))
		//val Background = ImageIO.read(File("assets/images/background.png"))

		// Draw Image
		ctx.drawStretchedRadialGradient(0xFF099AFE, 0xFF0942B4, 0, 0, imageW, imageH)

		var size = 40
		ctx.color = Color.WHITE
		ctx.font = ResourcesContext.burbankBigRegularBlack.deriveFont(40f)

		ctx.color = Color.YELLOW
		ctx.drawCenteredString("STONEWOOD", 271, 51)

		ctx.color = Color.YELLOW
		ctx.drawCenteredString("PLANKERTON", 731, 51)

		ctx.color = Color.YELLOW
		ctx.drawCenteredString("CANNY VALLEY", 1190, 51)

		ctx.color = Color.YELLOW
		ctx.drawCenteredString("TWINE PEAKS", 1650, 51)

		// BOTTOM
		ctx.color = Color.WHITE
		ctx.font = Font("Segoe UI", Font.BOLD or Font.ITALIC, 35)

		// Watermarks Text
		ctx.drawString(DiscordBot.instance?.discord?.selfUser?.name ?: "Development", 100, imageH - 55) // API

		// Watermarks Images
		val oldComposite = ctx.composite
		ctx.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .25f)
		//ctx.drawImage(watermark, imageW - 180, imageH - 180, 130, 130, null) // Watermark
		//ctx.drawImage(VBuck1, 25, imageH - 100, 62, 62, null) // API
		//ctx.drawImage(Twitter, 100 + ssw + 20, imageH - 100, 79, 64, null) // Twitter
		ctx.composite = oldComposite

		// TOP
		var textTime = SimpleDateFormat("EEEE, dd MMMM YYYY").format(Date())
		var textFortnite = "Save The World - Mission Alerts"

		var sz = 35
		ctx.color = Color.WHITE
		ctx.font = Font("Segoe UI", Font.BOLD or Font.ITALIC, sz)

		while (ctx.fontMetrics.stringWidth("$textFortnite $textTime") > imageW - 600) {
			sz--
			ctx.font = Font("Segoe UI", Font.BOLD or Font.ITALIC, sz)
			if (sz < 10) {
				break
			}
		}

		// Watermarks Images
		/*ctx.globalAlpha = "0.25"
		ctx.drawImage(Fortnite, 35, imageH - 180, 20, 47); // Fortnite
		ctx.drawImage(Time, ctx.measureText(textFortnite).width + 80, imageH - 180, 50, 50); // Date
		ctx.globalAlpha = "1"*/

		// Watermarks Text
		ctx.drawString(textFortnite, 70, imageH - 145) // Fortnite
		ctx.drawString(textTime, 60 + ctx.fontMetrics.stringWidth(textFortnite) + 80, imageH - 145) // Date

		fun drawEntries(ctx: Graphics2D, entries: List<MissionHolder>, col: Int) {
			entries.sortedByDescending { it.difficulty.RecommendedRating }.forEachIndexed { i, it ->
				it.draw(ctx, col * 460 + it.col * 20, i * 30)
			}
		}

		drawEntries(ctx, stonewood, 0)
		drawEntries(ctx, plankerton, 1)
		drawEntries(ctx, cannyvalley, 2)
		drawEntries(ctx, twinepeaks, 3)
	}
}

class MissionHolder(val theater: FortTheaterInfo.FortTheaterMapData, val mission: FortActiveTheaterInfo.FortAvailableMissionData, val missionAlert: FortActiveTheaterInfo.FortAvailableMissionAlertData) {
	val missionGenerator = loadCDO(mission.MissionGenerator.toString(), FortMissionGenerator::class.java)
	val difficulty = mission.MissionDifficultyInfo.getRowMapped<GameDifficultyInfo>()!!
	val tile = theater.Tiles[mission.TileIndex]
	val zoneTheme = loadCDO(tile.ZoneTheme.toString(), FortZoneTheme::class.java)
	val prominentAlertReward = missionAlert.MissionAlertRewards.items.first().asItemStack()
	val standoutType: EStandoutType

	init {
		val rarity = prominentAlertReward.rarity
		val isAccountResource = prominentAlertReward.primaryAssetType == "AccountResource"
		standoutType = when {
			prominentAlertReward.templateId == "Currency:currency_mtxswap" -> EStandoutType.Mtx
			!isAccountResource && rarity == EFortRarity.Mythic -> EStandoutType.Mythic
			!isAccountResource && rarity == EFortRarity.Legendary -> EStandoutType.Legendary
			!isAccountResource && rarity == EFortRarity.Epic -> EStandoutType.Epic
			else -> EStandoutType.None
		}
	}

	var row = 0
	var col = 0

	fun draw(ctx: Graphics2D, x: Int, y: Int) {
		val i: Number = when (standoutType) {
			EStandoutType.Mtx -> 0xB3ECBD45
			EStandoutType.Mythic -> TODO()
			EStandoutType.Legendary -> 0xB3D37841
			EStandoutType.Epic -> 0xB3B15BE2
			else -> 0x4D000000
		}

		// Shape
		ctx.color = i.awtColor()
		ctx.fillRect(x + 70, y + 70, 400, 25)

		// Mission Images
		val missionIcon = missionGenerator?.MissionIcon?.ResourceObject?.value as? UTexture2D
		if (missionIcon != null) {
			ctx.drawImage(missionIcon.toBufferedImage(), x + 73, y + 73, 20, 20, null)
		}

		// Power Level
		val ratingText = Formatters.num.format(difficulty.RecommendedRating)
		ctx.font = Font("Segoe UI", 1, 20)
		val plW = ctx.fontMetrics.stringWidth(ratingText)


		ctx.color = Color.WHITE
		ctx.drawString(ratingText, x + 106, y + 90)

		// Reward
		var rewardsSz = 20
		ctx.color = Color.WHITE
		ctx.font = Font("Segoe UI", 0, rewardsSz)
		val text = prominentAlertReward.render()

		while (ctx.fontMetrics.stringWidth(text) > 280) {
			rewardsSz--
			ctx.font = Font("Segoe UI", 0, rewardsSz)
			if (rewardsSz < 10) {
				break
			}
		}

		ctx.drawString(text, x + 160, y + 90)
		val rewardIcon = prominentAlertReward.getPreviewImagePath(false)?.load<UTexture2D>()
		if (rewardIcon != null) {
			ctx.drawImage(rewardIcon.toBufferedImage(), x + 70 + 400 - 22, y + 73, 20, 20, null)
		}
	}
}

enum class EStandoutType {
	Mtx, Mythic, Legendary, Epic, None
}