import com.tb24.discordbot.managers.HomebaseManager
import com.tb24.discordbot.ui.ExpeditionBuildSquadViewController
import org.junit.Assert.assertNotNull
import org.junit.Test

internal class ExpeditionsTest : TestCommon() {
	private val homebase: HomebaseManager

	init {
		val accountId = loadProfile("ComposeMCP-amrsatrio-queryprofile-campaign-106248").accountId
		homebase = HomebaseManager(accountId, api)
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