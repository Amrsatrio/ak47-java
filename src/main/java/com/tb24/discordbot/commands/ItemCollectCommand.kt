package com.tb24.discordbot.commands

import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.MapProcessor
import com.tb24.discordbot.Rune
import com.tb24.discordbot.images.MapImageGenerator
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.assetdata.FortLevelOverlayConfig
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin
import com.tb24.fn.util.Formatters.num
import com.tb24.uasset.JWPSerializer
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.ue4.objects.core.math.FVector2D
import me.fungames.jfortniteparse.ue4.objects.uobject.FSoftObjectPath
import me.fungames.jfortniteparse.util.toPngArray
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.*
import java.util.regex.Pattern
import javax.imageio.ImageIO

class ItemCollectCommand : BrigadierCommand("collectibles", "Shows collectibles you haven't collected this season.", arrayOf("xpcoins", "alienartifacts", "artifacts")) {
	companion object {
		private val XP_COINS_PATTERN = Pattern.compile("quest_s(\\d+)_w(\\d+)_xpcoins_(\\w+)")
		private val ALIEN_ARTIFACTS_PATTERN = Pattern.compile("quest_s(\\d+)_w(\\d+)_alienartifact_(\\w+)_(\\d+)")
		private val FISHTOON_PATTERN = Pattern.compile("quest_s(\\d+)_fishtoon_collectible_(\\w+)")
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source, true) }
		.then(literal("nomap")
			.executes { execute(it.source, false) }
		)
		.then(literal("generate")
			.requires(Rune::isBotDev)
			.executes { generate(it.source) }
		)

	private fun execute(source: CommandSourceStack, withMap: Boolean): Int {
		source.ensureSession()
		source.loading("Getting collectibles data")
		source.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val processed = TreeMap<Int, MutableMap<String, MutableMap<Int, Int>>>()
		val uncollected = mutableListOf<String>()
		val collected = mutableListOf<String>()

		for (item in athena.items.values) {
			val week: Int
			val questType: String
			val type: String
			val questIndex: Int
			var matcher = XP_COINS_PATTERN.matcher(item.primaryAssetName)
			if (matcher.matches()) {
				week = matcher.group(2).toInt() - 1
				questType = "xpcoins"
				type = matcher.group(3)
				questIndex = -1
			} else {
				matcher = ALIEN_ARTIFACTS_PATTERN.matcher(item.primaryAssetName)
				if (matcher.matches()) {
					week = matcher.group(2).toInt() - 1
					questType = "alienartifact"
					type = matcher.group(3)
					questIndex = matcher.group(4).toInt() - 1
				} else {
					matcher = FISHTOON_PATTERN.matcher(item.primaryAssetName)
					if (matcher.matches()) {
						week = 0 // New ones do not appear in later weeks
						questType = "fishtoon"
						type = matcher.group(2)
						questIndex = -1
					} else {
						continue
					}
					continue
				}
			}

			val weekData = processed.getOrPut(week) {
				mutableMapOf(
					/*"xpcoins_green" to TreeMap(),
					"xpcoins_blue" to TreeMap(),
					"xpcoins_purple" to TreeMap(),
					"xpcoins_gold" to TreeMap(),
					"alienartifact_purple" to TreeMap()*/
					"fishtoon_color01" to TreeMap(),
					"fishtoon_color02" to TreeMap(),
					"fishtoon_color03" to TreeMap(),
					"fishtoon_color04" to TreeMap(),
					"fishtoon_color05" to TreeMap(),
					"fishtoon_color06" to TreeMap(),
					"fishtoon_color07" to TreeMap(),
					"fishtoon_color08" to TreeMap(),
					"fishtoon_color09" to TreeMap(),
					"fishtoon_color10" to TreeMap(),
					"fishtoon_color11" to TreeMap(),
					"fishtoon_color12" to TreeMap(),
					"fishtoon_color13" to TreeMap(),
					"fishtoon_color14" to TreeMap(),
					"fishtoon_color15" to TreeMap(),
					"fishtoon_color16" to TreeMap(),
					"fishtoon_color17" to TreeMap(),
					"fishtoon_color18" to TreeMap(),
					"fishtoon_color19" to TreeMap(),
					"fishtoon_color20" to TreeMap(),
					"fishtoon_color21" to TreeMap(),
				)
			}
			val prefix = "completion_" + item.primaryAssetName + "_obj"

			for (entry in item.attributes.entrySet()) {
				val attrName = entry.key
				if (!attrName.startsWith(prefix)) continue

				val index = if (questIndex != -1) questIndex else {
					attrName.substring(prefix.length).toInt() // starts from 0 since season 14, 1 before
					// val index = attrName.substring(prefix.length).toInt() - 1;
				}
				val completionValue = entry.value.asInt
				weekData[questType + '_' + type]?.let {
					it[index] = completionValue
					if (completionValue == 0) {
						uncollected.add(attrName.substring("completion_".length))
					} else {
						collected.add(attrName.substring("completion_".length))
					}
				}
			}
		}

		/*val embed = source.createEmbed() TODO make embed work with S18 collectibles
		var overallCompleted = 0
		var overallMax = 0

		for ((weekNum, weekData) in processed) {
			var weekCompleted = 0
			var weekMax = 0
			val lines = mutableListOf<String>()

			for ((type, typeData) in weekData) {
				if (typeData.isEmpty()) continue

				var typeCompleted = 0
				var completionStr = ""

				for (objValue in typeData.values) {
					typeCompleted += objValue
					completionStr += if (objValue == 0) "." else "x"
				}

				weekCompleted += typeCompleted
				weekMax += typeData.size
				lines.add(L10N.format("collectibles.entry", L10N.format("collectibles.$type"), num.format(typeCompleted), num.format(typeData.size), if (completionStr.isNotEmpty()) completionStr else "-"))
			}

			overallCompleted += weekCompleted
			overallMax += weekMax
			embed.addField(L10N.format("collectibles.week", num.format(weekNum + 1), num.format(weekCompleted), num.format(weekMax)), if (lines.isEmpty()) "No entries" else lines.joinToString("\n"), true)
		}

		source.complete(null, embed
			.setTitle(L10N.format("collectibles.title", num.format((athena.stats as AthenaProfileStats).season_num)))
			.setDescription(L10N.format("collectibles.collected", num.format(overallCompleted), num.format(overallMax)))
			.build())
		if (!withMap || overallCompleted >= overallMax) {
			return Command.SINGLE_SUCCESS
		}*/

		source.loading("Generating and uploading map")
		val map = MapImageGenerator()
		val dataFile = File("config/xp_coins_data.json")
		if (!dataFile.exists()) {
			throw SimpleCommandExceptionType(LiteralMessage("We couldn't generate the map because the data does not exist.")).create()
		}

		val icGreen = ImageIO.read(File("canvas/201.png"))
		val icBlue = ImageIO.read(File("canvas/202.png"))
		val icPurple = ImageIO.read(File("canvas/203.png"))
		val icGold = ImageIO.read(File("canvas/204.png"))
		val icArtifactPurple = ImageIO.read(File("canvas/567.png"))
		val iconCache = hashMapOf<String, BufferedImage?>()

		for (xpCoin_ in FileReader(dataFile).use(JsonParser::parseReader).asJsonArray) {
			val xpCoin = xpCoin_.asJsonObject
			val hasCollected = collected.contains(xpCoin["questBackendName"].asString)

			map.markers.add(MapImageGenerator.MapMarker(xpCoin["loc"].asJsonObject.run { FVector2D(get("x").asFloat, get("y").asFloat) }) { ctx, x, y ->
				val firstTag = xpCoin["objStatTag"].asJsonArray[0].asString
				val iconPath = xpCoin["icon"]?.asString
				val ic = when {
					iconPath != null -> iconCache.getOrPut(iconPath) { loadObject<UTexture2D>(iconPath)?.toBufferedImage() } ?: return@MapMarker
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Green") -> icGreen
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Blue") -> icBlue
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Purple") -> icPurple
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Gold") -> icGold
					firstTag.startsWith("Athena.Quests.ItemCollect.AlienArtifact.Purple") -> icArtifactPurple
					else -> icGreen
				}
				val originalComposite = ctx.composite
				if (hasCollected) {
					ctx.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .2f)
				}
				val s = 56
				ctx.drawImage(ic, x - s / 2, y - s / 2, s, s, null)
				ctx.composite = originalComposite
			})
		}

		source.channel.sendFile(map.draw().toPngArray(), "XpCoins-${source.api.currentLoggedIn.displayName}-${athena.rvn}.png").complete()
		source.loadingMsg!!.delete().queue()
		return Command.SINGLE_SUCCESS
	}

	private inline fun generate(source: CommandSourceStack): Int {
		source.loading("Generating collectibles data. This will take a while")
		val start = System.currentTimeMillis()
		var mapPath = "/Game/Athena/Apollo/Maps/Apollo_Terrain"
		//mapPath = "/BattlepassS15/Maps/Apollo_ItemCollect_S15_Overlay" // All S15 Week 7+ XP Coins
		//mapPath = "/BattlepassS17/Maps/Apollo_ItemCollect_S17_Overlay" // All S17 Alien Artifacts
		// S18 Rainbow Ink are scattered across POI-based level overlays
		val overlays = hashMapOf<String, MutableList<FSoftObjectPath>>()
		loadObject<FortLevelOverlayConfig>("/BattlePassS18/Maps/BattlepassS18_LevelOverlay_Config")?.OverlayList?.forEach {
			val list = overlays.getOrPut(it.SourceWorld.toString()) { mutableListOf() }
			list.add(it.OverlayWorld)
		}
		val entries = MapProcessor().processMap(mapPath, overlays)
		FileWriter(File("config/xp_coins_data.json").apply { parentFile.mkdirs() }).use {
			JWPSerializer.GSON.toJson(entries, it)
		}
		source.complete("✅ Collectibles data has been generated in `${num.format(System.currentTimeMillis() - start)}ms`. Enjoy :)")
		return Command.SINGLE_SUCCESS
	}
}