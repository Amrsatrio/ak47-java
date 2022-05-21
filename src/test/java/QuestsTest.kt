import com.tb24.discordbot.images.createImage
import com.tb24.discordbot.ui.QuestsViewController
import com.tb24.uasset.AssetManager
import java.io.File

class QuestsTest : TestCommon() {
	init {
		AssetManager.INSTANCE.loadPaks()
		val athena = loadProfile("ComposeMCP-amrsatrio-queryprofile-athena-30256")
		val qc = QuestsViewController(athena)
		val category = qc.categories.first { it.backing.name == "QC_Noble" }

		//category.testWindow()

		File("test_quests_s19.png").writeBytes(category.createImage().encodeToData()!!.bytes)
	}
}

fun main() {
	QuestsTest()
}