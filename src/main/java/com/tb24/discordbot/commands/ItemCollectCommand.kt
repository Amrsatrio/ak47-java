package com.tb24.discordbot.commands

import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.*
import com.tb24.discordbot.images.MapImageGenerator
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.FortLevelOverlayConfig
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin
import com.tb24.fn.util.Formatters.num
import com.tb24.fn.util.getInt
import com.tb24.uasset.JWPSerializer
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleScheduleDefinition
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleScheduleDefinition.FortChallengeBundleScheduleEntry
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.ue4.objects.core.math.FVector2D
import me.fungames.jfortniteparse.ue4.objects.uobject.FSoftObjectPath
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.*

class ItemCollectCommand : BrigadierCommand("collectibles", "Shows collectibles you haven't collected this season.", arrayOf("xpcoins", "omnichips", "chips")) {
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
		val calendarResponse = source.api.fortniteService.calendarTimeline().exec().body()!!
		val clientEventsState = calendarResponse.channels["client-events"]!!.currentState

		val items = hashMapOf<String, FortItemStack>()
		for (item in athena.items.values) {
			if (item.primaryAssetType == "ChallengeBundleSchedule" || item.primaryAssetType == "Quest") {
				items[item.primaryAssetName] = item
			}
		}

		val schedules = mutableListOf<ScheduleData>()
		val states = hashMapOf<String, Boolean>()
		for (scheduleConfig in COLLECTIBLE_SCHEDULES) {
			val schedule = items[scheduleConfig.name] ?: continue
			val scheduleDef = schedule.defData as? FortChallengeBundleScheduleDefinition ?: continue
			val scheduleData = ScheduleData(scheduleConfig.displayName, scheduleDef)
			schedules.add(scheduleData)

			for (scheduleEntry in scheduleDef.ScheduleEntries) {
				val bundleDef = scheduleEntry.ChallengeBundle.load<FortChallengeBundleItemDefinition>() ?: continue
				if (bundleDef.GoalCardDisplayData != null) {
					continue
				}
				val numberAtEnd = bundleDef.name.substringAfterLast("_").toIntOrNull()
				val bundleName = numberAtEnd?.let { "Week $it" } ?: bundleDef.name
				val bundleData = BundleData(bundleName, bundleDef, scheduleEntry)
				scheduleData.bundles.add(bundleData)

				for (questInfo in bundleDef.QuestInfos) {
					val questDef = questInfo.QuestDefinition.load<FortQuestItemDefinition>() ?: continue
					val quest = items[questDef.name.toLowerCase()] // No quest means not yet available
					val shortName = questDef.DisplayName.toString().substringAfter(scheduleConfig.substringAfter)
					val questData = QuestData(shortName, questDef)
					bundleData.quests.add(questData)

					for (objective in questDef.Objectives) {
						val backendName = objective.BackendName.toString().toLowerCase()
						val completion = quest?.attributes?.getInt("completion_$backendName")
						if (completion != null) {
							questData.completions.add(completion)
							states[backendName] = completion >= objective.Count
						}
					}
				}
			}
		}
		if (schedules.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You have no collectibles.")).create()
		}

		val embeds = mutableListOf<MessageEmbed>()
		var hasUncompleted = false
		for (scheduleData in schedules) {
			val embed = (embeds.lastOrNull()?.let { EmbedBuilder().setColor(it.color) } ?: source.createEmbed())
				.setTitle(scheduleData.displayName)
			var scheduleCompleted = 0
			var scheduleMax = 0

			for (bundleData in scheduleData.bundles) {
				if (bundleData.quests.all { it.completions.isEmpty() }) {
					val scheduleDef = scheduleData.def
					val eventRecord = scheduleDef.CalendarEventTag?.let(clientEventsState::getEvent)
					embed.addField(bundleData.displayName, if (eventRecord != null) {
						 Date(eventRecord.activeSince.time + bundleData.scheduleEntry.UnlockValue * (24L * 60L * 60L * 1000L)).relativeFromNow()
					} else "Not yet available", true)
					continue
				}

				var bundleCompleted = 0
				var bundleMax = 0
				val lines = mutableListOf<String>()

				for (questData in bundleData.quests) {
					val completions = questData.completions
					var completed = 0
					//var completionStr = ""

					for (objValue in completions) {
						completed += objValue
						//completionStr += if (objValue == 0) "." else "x"
					}

					bundleCompleted += completed
					bundleMax += completions.size
					if (completed < completions.size) {
						lines.add("%s: **%,d** / %,d".format(questData.displayName, completed, completions.size) /*+ if (completionStr.isNotEmpty()) " `$completionStr`" else ""*/)
					}
				}

				scheduleCompleted += bundleCompleted
				scheduleMax += bundleMax
				if (bundleCompleted < bundleMax) {
					embed.addField("%s: %,d / %,d".format(bundleData.displayName, bundleCompleted, bundleMax), if (lines.isEmpty()) "No entries" else lines.joinToString("\n"), true)
				}
			}

			embed.setDescription((if (scheduleCompleted >= scheduleMax) "✅ " else "") + L10N.format("collectibles.collected", num.format(scheduleCompleted), num.format(scheduleMax)))
			embeds.add(embed.build())
			hasUncompleted = hasUncompleted || scheduleCompleted < scheduleMax
		}
		source.complete(MessageBuilder().setEmbeds(embeds).build())
		if (!withMap || !hasUncompleted) {
			return Command.SINGLE_SUCCESS
		}

		source.loading("Generating and uploading map")
		val map = MapImageGenerator()
		val dataFile = File("config/xp_coins_data.json")
		if (!dataFile.exists()) {
			throw SimpleCommandExceptionType(LiteralMessage("We couldn't generate the map because the data does not exist.")).create()
		}

		val iconCache = hashMapOf<String, BufferedImage?>()

		for (obj_ in FileReader(dataFile).use(JsonParser::parseReader).asJsonArray) {
			val obj = obj_.asJsonObject
			val backendName = obj["questBackendName"].asString
			val state = states[backendName] ?: continue

			map.markers.add(MapImageGenerator.MapMarker(obj["loc"].asJsonObject.run { FVector2D(get("x").asFloat, get("y").asFloat) }) { ctx, x, y ->
				val iconPath = obj["icon"]?.asString
				val ic = iconPath?.let { iconCache.getOrPut(iconPath) { loadObject<UTexture2D>(iconPath)?.toBufferedImage() } ?: return@MapMarker }
				val originalComposite = ctx.composite
				if (state) {
					ctx.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .2f)
				}
				val s = 56
				ic?.let { ctx.drawImage(it, x - s / 2, y - s / 2, s, s, null) }
				ctx.composite = originalComposite
			})
		}

		source.complete(AttachmentUpload(map.draw().toPngArray(), "Collectibles-${source.api.currentLoggedIn.displayName}-${athena.rvn}.png"))
		return Command.SINGLE_SUCCESS
	}

	private inline fun generate(source: CommandSourceStack): Int {
		source.loading("Generating collectibles data. This will take a while")
		val start = System.currentTimeMillis()
		val mapPath = "/Game/Athena/Artemis/Maps/Artemis_Terrain"
		val overlays = hashMapOf<String, MutableList<FSoftObjectPath>>()
		loadObject<FortLevelOverlayConfig>("/BattlePassS$SEASON_NUM/Maps/BattlepassS${SEASON_NUM}_LevelOverlayConfig")?.OverlayList?.forEach {
			val list = overlays.getOrPut(it.SourceWorldString) { mutableListOf() }
			list.add(it.OverlayWorld)
		}
		val entries = MapProcessor().processMap(mapPath, overlays)
		FileWriter(File("config/xp_coins_data.json").apply { parentFile.mkdirs() }).use {
			JWPSerializer.GSON.toJson(entries, it)
		}
		source.complete("✅ Collectibles data has been generated in `${num.format(System.currentTimeMillis() - start)}ms`. Enjoy :)")
		return Command.SINGLE_SUCCESS
	}

	class QuestData(val displayName: String, val def: FortQuestItemDefinition) {
		val completions = mutableListOf<Int>()
	}

	class BundleData(val displayName: String, val def: FortChallengeBundleItemDefinition, val scheduleEntry: FortChallengeBundleScheduleEntry) {
		val quests = mutableListOf<QuestData>()
	}

	class ScheduleData(val displayName: String, val def: FortChallengeBundleScheduleDefinition) {
		val bundles = mutableListOf<BundleData>()
	}
}