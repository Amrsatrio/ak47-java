package com.tb24.discordbot.commands

import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.L10N
import com.tb24.discordbot.MapImageGenerator
import com.tb24.discordbot.MapProcessor
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.attributes.AthenaProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin
import com.tb24.fn.util.Formatters.num
import com.tb24.uasset.AssetManager
import com.tb24.uasset.JWPSerializer
import me.fungames.jfortniteparse.ue4.objects.core.math.FVector2D
import me.fungames.jfortniteparse.util.toPngArray
import java.awt.AlphaComposite
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.*
import java.util.regex.Pattern
import javax.imageio.ImageIO
import kotlin.system.exitProcess

class XpCoinsCommand : BrigadierCommand("xpcoins", "Shows XP coins you haven't collected this season.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source, true) }
		.then(literal("nomap")
			.executes { execute(it.source, false) }
		)

	private fun execute(source: CommandSourceStack, withMap: Boolean): Int {
		source.ensureSession()
		source.loading("Getting XP coins data")
		source.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val processed = TreeMap<Int, MutableMap<String, MutableMap<Int, Int>>>()
		val uncollected = mutableListOf<String>()

		for (item in athena.items.values) {
			val tidMatch = Pattern.compile("quest_s(\\d+)_w(\\d+)_xpcoins_(\\w+)").matcher(item.primaryAssetName)
			if (!tidMatch.matches()) continue

			//val season = tidMatch.group(1).toInt() - 1;
			val week = tidMatch.group(2).toInt() - 1
			val type = tidMatch.group(3)

			val weekData = processed.getOrPut(week) { mutableMapOf("green" to TreeMap(), "blue" to TreeMap(), "purple" to TreeMap(), "gold" to TreeMap()) }
			val prefix = "completion_" + item.primaryAssetName + "_obj"

			for (entry in item.attributes.entrySet()) {
				val attrName = entry.key
				if (!attrName.startsWith(prefix)) continue

				val index = attrName.substring(prefix.length).toInt() // starts from 0 since season 14, 1 before
				// val index = attrName.substring(prefix.length).toInt() - 1;
				val completionValue = entry.value.asInt
				weekData[type]!![index] = completionValue

				if (completionValue == 0) {
					uncollected.add(attrName.substring("completion_".length))
				}
			}
		}

		val embed = source.createEmbed()
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
				lines.add(L10N.format("xpcoins.entry", L10N.format("xpcoins.$type"), num.format(typeCompleted), num.format(typeData.size), if (completionStr.isNotEmpty()) completionStr else "-"))
			}

			overallCompleted += weekCompleted
			overallMax += weekMax
			embed.addField(L10N.format("xpcoins.week", num.format(weekNum + 1), num.format(weekCompleted), num.format(weekMax)), if (lines.isEmpty()) "No entries" else lines.joinToString("\n"), true)
		}

		source.complete(null, embed
			.setTitle("Season ${num.format((athena.stats.attributes as AthenaProfileAttributes).season_num)} XP Coins")
			.setDescription(L10N.format("xpcoins.collected", overallCompleted, overallMax))
			.build())
		if (!withMap || overallCompleted >= overallMax) {
			return Command.SINGLE_SUCCESS
		}

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

		for (xpCoin_ in FileReader(dataFile).use(JsonParser::parseReader).asJsonArray) {
			val xpCoin = xpCoin_.asJsonObject
			val hasCollected = uncollected.indexOf(xpCoin["questBackendName"].asString) == -1
			//if (hasCollected) continue

			map.markers.add(MapImageGenerator.MapMarker(xpCoin["loc"].asJsonObject.run { FVector2D(get("x").asFloat, get("y").asFloat) }) { ctx, x, y ->
				val firstTag = xpCoin["objStatTag"].asJsonArray[0].asString
				val ic = when {
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Green") -> icGreen
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Blue") -> icBlue
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Purple") -> icPurple
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Gold") -> icGold
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
}

class GenXpCoinsDataCommand : BrigadierCommand("genxpcoinsdata", "Generate XP coins data based on the current loaded game files.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			c.source.loading("Generating XP coins data")
			val start = System.currentTimeMillis()
			var mapPath = "/Game/Athena/Apollo/Maps/Apollo_Terrain"
			mapPath = "/BattlepassS15/Maps/Apollo_ItemCollect_S15_Overlay" // all s15 w7+ xp coins are contained in this map
			val entries = MapProcessor().processMap(mapPath)
			FileWriter(File("config/xp_coins_data.json").apply { parentFile.mkdirs() }).use {
				JWPSerializer.GSON.toJson(entries, it)
			}
			c.source.complete("✅ XP coins data has been generated in `${num.format(System.currentTimeMillis() - start)}ms`. Enjoy :)")
			Command.SINGLE_SUCCESS
		}
}

fun main() {
	AssetManager.INSTANCE.loadPaks()
	val dataFile = File("config/xp_coins_data.json")
	arrayOf(12, 13, 14, 15, 16).forEach { n ->
		val map = MapImageGenerator()
		val icGreen = ImageIO.read(File("canvas/201.png"))
		val icBlue = ImageIO.read(File("canvas/202.png"))
		val icPurple = ImageIO.read(File("canvas/203.png"))
		val icGold = ImageIO.read(File("canvas/204.png"))

		for (xpCoin_ in FileReader(dataFile).use(JsonParser::parseReader).asJsonArray) {
			val xpCoin = xpCoin_.asJsonObject
			if (!xpCoin["questBackendName"].asString.contains("w%02d".format(n))) continue

			map.markers.add(MapImageGenerator.MapMarker(xpCoin["loc"].asJsonObject.run { FVector2D(get("x").asFloat, get("y").asFloat) }) { ctx, x, y ->
				val firstTag = xpCoin["objStatTag"].asJsonArray[0].asString
				val ic = when {
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Green") -> icGreen
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Blue") -> icBlue
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Purple") -> icPurple
					firstTag.startsWith("Athena.Quests.ItemCollect.XPCoin.Gold") -> icGold
					else -> icGreen
				}
				val s = 56
				ctx.drawImage(ic, x - s / 2, y - s / 2, s, s, null)
			})
		}
		File("xp_coins_w$n.png").writeBytes(map.draw().toPngArray())
	}
	exitProcess(0)
}