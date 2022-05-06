import com.google.gson.JsonParser
import com.tb24.discordbot.managers.CatalogManager
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.to
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortCmsData
import com.tb24.fn.model.gamesubcatalog.CatalogDownload
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.uasset.AssetManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileReader

open class TestCommon {
	val api = EpicApi(OkHttpClient())

	init {
		AssetManager.INSTANCE.loadPaks()
	}

	fun loadProfile(s: String): McpProfile {
		val profile = FileReader("D:\\Downloads\\ComposeMCP\\$s.json").use {
			val d = JsonParser.parseReader(it).asJsonObject
			EpicApi.GSON.fromJson(d, McpProfile::class.java)
		}
		api.profileManager.localProfileGroup.profileData[profile.profileId] = profile
		return profile
	}

	fun createCatalog(catalogPath: String): CatalogManager {
		val catalogManager = CatalogManager()
		catalogManager.catalogData = FileReader(catalogPath).use { EpicApi.GSON.fromJson(it, CatalogDownload::class.java) }
		catalogManager.sectionsData = api.okHttpClient.newCall(Request.Builder().url("https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game/shop-sections").build()).exec().to<FortCmsData.ShopSectionsData>()
		catalogManager.validate()
		return catalogManager
	}
}