import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tb24.discordbot.images.createImage
import com.tb24.discordbot.ui.QuestsViewController
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.QuestCategoryData
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import java.io.File
import java.io.FileWriter
import java.util.*

class QuestsTest : TestCommon() {
	init {
		AssetManager.INSTANCE.loadPaks()
		//gen()
		val athena = loadProfile("gen")
		val qc = QuestsViewController(athena)
		val category = qc.categories.first { it.backing.name == "QC_S21_NoSweatSummer" }

		//category.testWindow()

		File("test_quests_s19.png").writeBytes(category.createImage(2f).encodeToData()!!.bytes)
	}
}

fun gen() {
	val items = hashMapOf<String, FortItemStack>()
	val category = loadObject<QuestCategoryData>("/BattlePassS21/Items/QuestItems/Categories/QC_S21_NoSweatSummer")!!
	//val category = loadObject<QuestCategoryData>("/Game/Athena/HUD/MiniMap/Categories/QC_Weekly")!!
	val challengeBundles = AssetManager.INSTANCE.assetRegistry.templateIdToAssetDataMap.values.filter { d ->
		d.assetClass == "FortChallengeBundleItemDefinition" && loadObject<FortChallengeBundleItemDefinition>(d.objectPath)!!.let { def -> category.IncludeTags == null || category.IncludeTags.any { def.GameplayTags?.getValue(it.toString()) != null } }
	}

	fun addBundle(b: FortChallengeBundleItemDefinition) {
		val ids = mutableListOf<String>()
		val k = UUID.randomUUID().toString()
		for (qi in b.QuestInfos) {
			val qd = qi.QuestDefinition.load<FortQuestItemDefinition>()!!
			val q = FortItemStack("Quest", qd.name.lowercase(), 1).apply {
				attributes.apply {
					addProperty("challenge_bundle_id", k)
					addProperty("quest_state", "Active")
				}
			}
			val qk = UUID.randomUUID().toString()
			items[qk] = q
			ids.add(qk)
		}
		items[k] = FortItemStack("ChallengeBundle", b.name.lowercase(), 1).apply {
			attributes.apply {
				add("grantedquestinstanceids", JsonArray().apply {
					ids.forEach(::add)
				})
			}
		}
	}

	for (b in challengeBundles) {
		addBundle(loadObject<FortChallengeBundleItemDefinition>(b.objectPath)!!)
	}

	FileWriter("D:\\Downloads\\ComposeMCP\\gen.json").use {
		EpicApi.GSON.toJson(JsonObject().apply {
			add("items", EpicApi.GSON.toJsonTree(items))
			add("stats", JsonObject().apply {
				add("attributes", JsonObject())
			})
		}, it)
	}
}

fun main() {
	QuestsTest()
}