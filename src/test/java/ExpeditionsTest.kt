import com.google.gson.JsonParser
import com.tb24.discordbot.managers.HomebaseManager
import com.tb24.discordbot.ui.ExpeditionBuildSquadViewController
import com.tb24.fn.EpicApi
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.util.getString
import com.tb24.uasset.AssetManager
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.FileReader

internal class ExpeditionsTest {
	val api = EpicApi(OkHttpClient())
	val homebase: HomebaseManager

	init {
		AssetManager.INSTANCE.loadPaks()
		homebase = HomebaseManager("", api)
		loadProfile("ComposeMCP-amrsatrio-queryprofile-campaign-106248")
	}

	fun loadProfile(s: String) {
		FileReader("D:\\Downloads\\ComposeMCP\\$s.json").use {
			val d = JsonParser.parseReader(it).asJsonObject
			api.profileManager.localProfileGroup.profileData[d.getString("profileId")] = EpicApi.GSON.fromJson(d, McpProfile::class.java)
		}
	}

	@Test
	fun abc() {
		val campaign = api.profileManager.getProfileData("campaign")
		val expedition = campaign.items.values.first { it.templateId == "Expedition:expedition_choppingwood_t00" }
		val context = ExpeditionBuildSquadViewController(expedition, homebase)
		//context.
		assertNotNull(expedition)
	}
}