package com.tb24.discordbot

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tb24.fn.model.assetdata.BuildingGameplayActorPropQuest
import com.tb24.uasset.JWPSerializer
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.exceptions.ParserException
import me.fungames.jfortniteparse.fort.exports.actors.BuildingFoundation
import me.fungames.jfortniteparse.ue4.assets.exports.UObject
import me.fungames.jfortniteparse.ue4.assets.exports.UWorld
import me.fungames.jfortniteparse.ue4.assets.exports.actors.AActor
import me.fungames.jfortniteparse.ue4.objects.core.math.FRotator
import me.fungames.jfortniteparse.ue4.objects.core.math.FTransform
import me.fungames.jfortniteparse.ue4.objects.core.math.FVector
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTagContainer
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import me.fungames.jfortniteparse.ue4.objects.uobject.FSoftObjectPath

class MapProcessor {
	val entries = JsonArray()

	@Suppress("USELESS_ELVIS")
	fun processMap(mapPath: String = "/Game/Athena/Apollo/Maps/Apollo_Terrain",
				   overlays: Map<String, List<FSoftObjectPath>> = emptyMap(),
				   parentLoc: FVector = FVector(0f, 0f, 0f),
				   parentRot: FRotator = FRotator(0f, 0f, 0f),
				   parentScale: FVector = FVector(1f, 1f, 1f)): JsonArray {
		val world = loadObject<UWorld>(mapPath)
			?: throw ParserException("$mapPath is not a world")
		DiscordBot.LOGGER.info("Processing $mapPath")
		val aggregatedActors = mutableListOf<Lazy<UObject>?>()
		val aggregatedStreamingLevels = mutableListOf<Lazy<UObject>?>()
		val persistentLevel = world.persistentLevel?.value
		if (persistentLevel != null) {
			aggregatedActors.addAll(persistentLevel.actors)
		}
		aggregatedStreamingLevels.addAll(world.streamingLevels)
		overlays[world.getPathName()]?.forEach {
			val overlayWorld = it.load<UWorld>()
			if (overlayWorld != null) {
				val overlayPersistentLevel = overlayWorld.persistentLevel?.value
				if (overlayPersistentLevel != null) {
					aggregatedActors.addAll(overlayPersistentLevel.actors)
				}
				aggregatedStreamingLevels.addAll(overlayWorld.streamingLevels)
			}
		}
		for (lazy in aggregatedActors) {
			val actor = lazy?.value as? AActor
				?: continue
			var relativeLocation = FVector(0f, 0f, 0f)
			var relativeRotation = FRotator(0f, 0f, 0f)
			var relativeScale3D = FVector(1f, 1f, 1f)
			actor.RootComponent?.value?.apply {
				RelativeLocation?.let { relativeLocation = it }
				RelativeRotation?.let { relativeRotation = it }
				RelativeScale3D?.let { relativeScale3D = it }
			}
			val objectLoc = parentLoc + parentScale * parentRot.rotateVector(relativeLocation)
			val objectRot = parentRot + relativeRotation
			val objectScale = parentScale * relativeScale3D
			if (actor.exportType.startsWith("BP_ItemCollection_")) {
				addEntry(actor.getOrNull<FName>("QuestBackendName"), actor.getOrNull<FGameplayTagContainer>("ObjStatTag"), objectLoc)
			}
			if (actor is BuildingGameplayActorPropQuest) { // S17: BP_S17_AlienArtifact_Variant1_C
				val consolidatedQuestComponent = actor.ConsolidatedQuestComponent?.value ?: continue
				addEntry(consolidatedQuestComponent.ObjectiveBackendName, actor.StaticGameplayTags, objectLoc, actor.QuestIconComponent?.value?.MapIconData?.MapIcon?.resolvedObject?.getPathName())
			}
			if (actor is BuildingFoundation) {
				actor.AdditionalWorlds?.forEach {
					processMap(it.toString(), overlays, objectLoc, objectRot, objectScale)
				}
			}
		}
		for (lazy in aggregatedStreamingLevels) {
			val streamingLevel = lazy?.value
				?: continue
			val transform = streamingLevel.getOrNull<FTransform>("LevelTransform") ?: FTransform()
			val objectLoc = parentLoc + parentScale * (transform.translation ?: FVector()) // TODO parentRot.rotateVector(relativeLocation)
			val objectRot = parentRot // TODO + (transform.rotation ?: FQuat())
			val objectScale = parentScale * (transform.scale3D ?: FVector(1f))
			streamingLevel.getOrNull<FSoftObjectPath>("WorldAsset")?.apply {
				processMap(toString(), overlays, objectLoc, objectRot, objectScale)
			}
		}
		return entries
	}

	private fun addEntry(questBackendName: FName?, tags: FGameplayTagContainer?, location: FVector, icon: String? = null) {
		if (tags == null) return
		entries.add(JsonObject().apply {
			addProperty("questBackendName", questBackendName?.text)
			add("objStatTag", JWPSerializer.GSON.toJsonTree(tags.gameplayTags))
			add("loc", JWPSerializer.GSON.toJsonTree(location))
			if (icon != null) {
				addProperty("icon", icon)
			}
		})
	}
}