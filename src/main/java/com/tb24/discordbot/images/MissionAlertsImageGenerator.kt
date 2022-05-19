package com.tb24.discordbot.images

import com.tb24.discordbot.commands.iterateMissions
import com.tb24.discordbot.commands.rarityData
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
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.fort.objects.rows.GameDifficultyInfo
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.util.drawCenteredString
import me.fungames.jfortniteparse.util.toPngArray
import okhttp3.OkHttpClient
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

fun generateMissionAlertsImage(resp: FortActiveTheaterInfo?): BufferedImage {
	val stonewood = mutableListOf<MissionHolder>()
	val plankerton = mutableListOf<MissionHolder>()
	val cannyvalley = mutableListOf<MissionHolder>()
	val twinepeaks = mutableListOf<MissionHolder>()
	resp?.iterateMissions { theater, mission, missionAlert ->
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
	val top = 64 + 128 + 48
	val imageH = top + 70 + 30 * height + 64
	return createAndDrawCanvas(imageW, imageH) { ctx ->
		// Background
		ctx.drawStretchedRadialGradient(0xFF099AFE, 0xFF0942B4, 0, 0, imageW, imageH)

		// Header
		val powerIcon = loadObject<UTexture2D>("/Game/UI/Foundation/Textures/Icons/Shell/T-Icon-Power-128.T-Icon-Power-128")?.toBufferedImage()
		val textTime = SimpleDateFormat("EEEE, dd MMMM yyyy").format(Date())
		ctx.drawHeader(powerIcon, "Mission Alerts", textTime, 64, 64, 1f)

		ctx.color = Color.WHITE
		ctx.font = ResourcesContext.burbankBigRegularBlack.deriveFont(40f)

		ctx.color = ResourcesContext.primaryColor.awtColor()
		ctx.drawCenteredString("STONEWOOD", 271, top + ctx.fontMetrics.ascent)

		ctx.color = ResourcesContext.primaryColor.awtColor()
		ctx.drawCenteredString("PLANKERTON", 731, top + ctx.fontMetrics.ascent)

		ctx.color = ResourcesContext.primaryColor.awtColor()
		ctx.drawCenteredString("CANNY VALLEY", 1190, top + ctx.fontMetrics.ascent)

		ctx.color = ResourcesContext.primaryColor.awtColor()
		ctx.drawCenteredString("TWINE PEAKS", 1650, top + ctx.fontMetrics.ascent)

		fun drawEntries(ctx: Graphics2D, entries: List<MissionHolder>, col: Int) {
			entries.sortedByDescending { it.difficulty.RecommendedRating }.forEachIndexed { i, it ->
				it.draw(ctx, col * 460 + it.col * 20 + 70, top + 70 + i * 30)
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
	val backgroundColor: Int

	init {
		val rarity = prominentAlertReward.rarity
		val isAccountResource = prominentAlertReward.primaryAssetType == "AccountResource"
		backgroundColor = when {
			prominentAlertReward.templateId == "Currency:currency_mtxswap" -> 0xB3ECBD45.toInt()
			!isAccountResource && rarity >= EFortRarity.Epic -> (0xB3 shl 24) or (rarityData.forRarity(rarity).Color2.toFColor(true).toPackedARGB() and 0xFFFFFF)
			else -> 0x4D000000
		}
	}

	var row = 0
	var col = 0

	fun draw(ctx: Graphics2D, x: Int, y: Int) {
		// Shape
		ctx.color = backgroundColor.awtColor()
		ctx.fillRect(x, y, 400, 25)

		// Mission Images
		val missionIcon = missionGenerator?.MissionIcon?.ResourceObject?.value as? UTexture2D
		if (missionIcon != null) {
			ctx.drawImage(missionIcon.toBufferedImage(), x + 3, y + 3, 20, 20, null)
		}

		// Power Level
		val ratingText = Formatters.num.format(difficulty.RecommendedRating)
		ctx.font = Font("Segoe UI", 1, 20)

		ctx.color = Color.WHITE
		ctx.drawString(ratingText, x + 36, y + 20)

		// Reward
		var rewardsSz = 20
		ctx.color = Color.WHITE
		ctx.font = Font("Segoe UI", 0, rewardsSz)
		val text = prominentAlertReward.render(showIcons = false)

		while (ctx.fontMetrics.stringWidth(text) > 280) {
			rewardsSz--
			ctx.font = Font("Segoe UI", 0, rewardsSz)
			if (rewardsSz < 10) {
				break
			}
		}

		ctx.drawString(text, x + 86, y + 20)
		val rewardIcon = prominentAlertReward.getPreviewImagePath(false)?.load<UTexture2D>()
		if (rewardIcon != null) {
			ctx.drawImage(rewardIcon.toBufferedImage(), x + 400 - 22, y + 3, 20, 20, null)
		}
	}
}

enum class EStandoutType {
	Mtx, Mythic, Legendary, Epic, None
}