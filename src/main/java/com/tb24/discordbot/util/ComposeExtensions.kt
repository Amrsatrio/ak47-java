package com.tb24.discordbot.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.tb24.uasset.AssetManager
import org.jetbrains.skia.Data as SkData
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Surface as SkSurface
import org.jetbrains.skia.Typeface as SkTypeface

object ComposeResources {
	val burbankSmallBold by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/burbanksmall-bold.ufont") }
	val burbankSmallBlack by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/burbanksmall-black.ufont") }
	val burbankBigRegularBold by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigRegular-Bold.ufont") }
	val burbankBigRegularBlack by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigRegular-Black.ufont") }
	val burbankBigCondensedBlack by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigCondensed-Black.ufont") }
	val headingStyle by lazy { TextStyle(fontSize = 36.sp, fontFamily = burbankBigRegularBlack) }
	val bodyStyle by lazy { TextStyle(fontSize = 20.sp, fontFamily = burbankSmallBold) }

	private fun fromPaks(path: String) = FontFamily(Typeface(SkTypeface.makeFromData(SkData.makeFromBytes(AssetManager.INSTANCE.provider.saveGameFile(path)!!))))
}

fun renderCompose(density: Float = 1f, content: @Composable () -> Unit): SkImage {
	val scene = ComposeScene(density = Density(density))
	scene.setContent(content)
	val surface = SkSurface.makeRasterN32Premul(scene.contentSize.width, scene.contentSize.height)
	scene.render(surface.canvas, System.nanoTime())
	scene.close()
	val image = surface.makeImageSnapshot()
	surface.close()
	return image
}

fun Modifier.shear(x: Float = 0f, y: Float = 0f) = drawWithContent {
	val canvas = drawContext.canvas
	canvas.save()
	val half = drawContext.size.height / 2f
	canvas.translate(0f, half)
	canvas.skew(x, y)
	canvas.translate(0f, -half)
	drawContent()
	canvas.restore()
}