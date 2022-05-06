import com.tb24.discordbot.images.generateShopImage
import me.fungames.jfortniteparse.util.toPngArray
import org.junit.Test
import java.io.File

class TestShopImageGenerator : TestCommon() {
	@Test
	fun test() {
		val catalog = createCatalog("D:/Downloads/shop-09-05-2021-en.json")
		File("out.png").writeBytes(generateShopImage(catalog, 2).toPngArray())
	}
}