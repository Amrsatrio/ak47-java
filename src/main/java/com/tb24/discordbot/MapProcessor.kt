package com.tb24.discordbot

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tb24.uasset.JWPSerializer
import com.tb24.uasset.MyFileProvider
import me.fungames.jfortniteparse.ue4.assets.exports.actors.AActor
import me.fungames.jfortniteparse.ue4.objects.core.math.FRotator
import me.fungames.jfortniteparse.ue4.objects.core.math.FVector
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTagContainer
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import me.fungames.jfortniteparse.ue4.objects.uobject.FPackageIndex
import me.fungames.jfortniteparse.ue4.objects.uobject.FSoftObjectPath

@ExperimentalUnsignedTypes
class MapProcessor(val provider: MyFileProvider) {
	val entries = JsonArray()

	fun processMap(mapPath: String = "/Game/Content/Athena/Apollo/Maps/Apollo_Terrain.umap",
				   parentLoc: FVector = FVector(0f, 0f, 0f),
				   parentRot: FRotator = FRotator(0f, 0f, 0f),
				   parentScale: FVector = FVector(1f, 1f, 1f)): JsonArray {
		provider.loadGameFile(mapPath)!!.exports.forEach { export ->
			var relativeLocation = FVector(0f, 0f, 0f)
			var relativeRotation = FRotator(0f, 0f, 0f)
			var relativeScale3D = FVector(1f, 1f, 1f)

			(export as? AActor)?.RootComponent?.value?.apply {
				RelativeLocation?.let { relativeLocation = it }
				RelativeRotation?.let { relativeRotation = it }
				RelativeScale3D?.let { relativeScale3D = it }
			}

			val objectLoc = parentLoc + parentScale * parentRot.rotateVector(relativeLocation)
			val objectRot = parentRot + relativeRotation
			val objectScale = parentScale * relativeScale3D
			val collectItemQuest: FPackageIndex? = export.get("CollectItemQuest")
			val questBackendName: FName? = export.get("QuestBackendName")
			val objStatTag: FGameplayTagContainer? = export.get("ObjStatTag")

			if (objStatTag != null) {
//				collectItemQuest?.let { quests[collectItemQuest.importObject?.objectName?.text!!] = provider.loadObject(it) }
				entries.add(JsonObject().apply {
//					addProperty("collectItemQuest", collectItemQuest?.importObject?.objectName?.text)
					addProperty("questBackendName", questBackendName?.text)
					add("objStatTag", JWPSerializer.GSON.toJsonTree(objStatTag.gameplayTags))
					add("loc", JWPSerializer.GSON.toJsonTree(objectLoc))
				})
			}

			export.getOrNull<Array<FSoftObjectPath>>("AdditionalWorlds")?.forEach {
				processMap(it.assetPathName.text.substringBeforeLast('.') + ".umap", objectLoc, objectRot, objectScale)
			}

			if (export.exportType == "LevelStreamingAlwaysLoaded") {
				export.getOrNull<FSoftObjectPath>("WorldAsset")?.apply {
					processMap(assetPathName.text.substringBeforeLast('.') + ".umap", objectLoc, objectRot, objectScale)
				}
			}
		}
		return entries
	}
}