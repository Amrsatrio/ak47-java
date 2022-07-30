package com.tb24.discordbot.images

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.google.accompanist.flowlayout.FlowColumn
import com.tb24.discordbot.commands.getQuestCompletion
import com.tb24.discordbot.ui.QuestsViewController
import com.tb24.discordbot.util.ComposeResources
import com.tb24.discordbot.util.description
import com.tb24.discordbot.util.renderCompose
import com.tb24.discordbot.util.shear
import com.tb24.fn.util.format
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleItemDefinition
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Shader
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import org.jetbrains.skia.BlendMode as SkBlendMode

@Composable
fun QuestList(category: QuestsViewController.QuestCategory, modifier: Modifier = Modifier) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
		for (goalCard in category.goalCards.values) {
			GoalsPageQuestCard(goalCard)
		}
		for (header in category.headers) {
			Header(header.name.format()!!)
			for (quest in header.quests) {
				AthenaChallengeListEntry_NpcQuest(quest)
			}
		}
	}
}

fun makeCompositeShader(vararg shaders: Shader): Shader {
	if (shaders.size == 1) return shaders[0]
	var shader = shaders[0]
	for (i in 1 until shaders.size) {
		shader = Shader.makeBlend(SkBlendMode.SRC_OVER, shader, shaders[i])
	}
	return shader
}

@Composable
fun QuestList2(category: QuestsViewController.QuestCategory) {
	val w = (946-41*2).dp
	var hackWidth by remember { mutableStateOf(0) }

	// Process icon
	val mm = FMergedMaterialParams(category.backing.DisplayAsset.load()!!)
	val colorA = mm.vector["RGB1"]!!
	val colorB = mm.vector["RGB2"]!!
	val icon = (mm.texture["NPC-Portrait"]!!.value as UTexture2D).toBufferedImage().toComposeImageBitmap()
	val iconScale = mm.scalar["Zoom"]!!
	val iconOffsetXPct = mm.scalar["Itemposition_U"]!!
	val iconOffsetYPct = mm.scalar["Itemposition_V"]!!
	val gradientDensity = mm.scalar["Radial Gradient Density"]!!
	val triangleOpacity = mm.scalar["TriangleDensity"]!!

	// Process description
	val def = category.challengeBundles.values.firstOrNull()?.defData as? FortChallengeBundleItemDefinition
	val description = def?.Description.format()
	val lockedDisplayTextOverride = def?.LockedDisplayTextOverride.format()
	val combined = (description.orEmpty() + '\n' + lockedDisplayTextOverride.orEmpty()).trim()

	Column(
		Modifier
			.onSizeChanged { hackWidth = it.width }
			.blueGradientBackground()
			//.background(ShaderBrush(triangleShader))
	) {
		Box(
			Modifier
				.width(if (hackWidth == 0) Dp.Unspecified else with(LocalDensity.current) { hackWidth.toDp() })
				.height(135.dp)
				.background(Brush.horizontalGradient(listOf(Color(0xFF01258F), Color(0xFF01329D))))
				.drawWithContent {
					val effectStart = size.width - 500.dp.toPx()
					val composite = Shader.makeBlend(SkBlendMode.SRC_IN, Shader.makeLinearGradient(effectStart, 0f, size.width, 0f, intArrayOf(0x00FFFFFF, 0x7FFFFFFF)), triangleShader)
					drawRect(ShaderBrush(composite), topLeft = Offset(effectStart, 0f))
					drawContent()
				}
		) {
			Canvas(Modifier.aspectRatio(153f / 135f).fillMaxHeight()) {
				val mask = Path().apply {
					moveTo(0f, 0f)
					lineTo(size.width, 0f)
					lineTo(size.width - tan(Math.toRadians(8.0)).toFloat() * size.height, size.height)
					lineTo(0f, size.height)
					close()
				}
				clipPath(mask) {
					drawRect(Color(colorA))
					drawRect(Brush.radialGradient(listOf(Color(colorB), Color(colorB and 0xFFFFFF)), Offset(size.width / 2, size.height), size.height), alpha = gradientDensity.coerceAtMost(1f)) // TODO > 1 values, for example 1.7
					drawRect(ShaderBrush(triangleShader), alpha = triangleOpacity)
					val scale = min(size.width / icon.width.toFloat(), size.height / icon.height.toFloat()) * iconScale
					val sx = scale
					val sy = scale
					val tx = (size.width - icon.width * sx) / 2 - iconOffsetXPct * size.width
					val ty = (size.height - icon.height * sy) / 2 - iconOffsetYPct * size.height
					drawImage(
						image = icon,
						dstOffset = IntOffset(tx.toInt(), ty.toInt()),
						dstSize = IntSize((icon.width * sx).toInt(), (icon.height * sy).toInt())
					)
				}
			}
			Column(Modifier.padding(start = 135.dp, top = 4.dp, bottom = 4.dp)) {
				Text(
					text = category.backing.DisplayName.format()!!.uppercase(),
					style = ComposeResources.headingStyle,
					modifier = Modifier.shear(-0.2f).padding(start = 30.dp),
					color = Color.White
				)
				Text(
					text = combined,
					color = Color(0xFFC0ECFF),
					style = ComposeResources.bodyStyle,
					modifier = Modifier.shear(-0.1f).padding(start = 25.dp)
				)
			}
		}
		FlowColumn(Modifier.padding(41.dp, 29.dp), mainAxisSpacing = 7.dp, crossAxisSpacing = 41.dp, mainAxisTarget = 1800.dp) {
			for (goalCard in category.goalCards.values) {
				Box(Modifier.width(w)) {
					GoalsPageQuestCard(goalCard)
				}
			}
			for (header in category.headers) {
				Column(Modifier.width(w), verticalArrangement = Arrangement.spacedBy(7.dp)) {
					Header(header.name.format()!!)
					for (quest in header.quests) {
						AthenaChallengeListEntry_NpcQuest(quest)
					}
				}
			}
		}
	}
}

val triangleShader by lazy {
	loadImageBitmap(FileInputStream("canvas/triangles.png")).asSkiaBitmap().makeShader(
		FilterTileMode.REPEAT,
		FilterTileMode.REPEAT,
		Matrix33.makeRotate(15f)
	)
}

@Composable
fun GoalsPageQuestCard(goalCard: QuestsViewController.GoalCard) {
	Column(
		Modifier
			.clip(RoundedCornerShape(12.dp))
			.background(Color(0x19C0ECFF))
	) {
		// Header
		Row(
			Modifier
				.fillMaxWidth().height(66.dp)
				.background(Color(0x7F001166))
				.padding(horizontal = 23.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			// Title
			Text(
				text = (goalCard.displayData.HeaderText?.format() ?: "Bonus Goals").uppercase(),
				modifier = Modifier.shear(-0.2f),
				color = Color.White,
				style = ComposeResources.headingStyle)
			Spacer(Modifier.weight(1f))
			// Time remaining
			Row(
				Modifier.alpha(0f), // TODO Timer
				verticalAlignment = Alignment.CenterVertically
			) {
				Box(Modifier.size(24.dp, 24.dp).background(color = Color(0xFFEEEEEE)))
				Text(
					text = "4h 20m".uppercase(),
					color = Color.White,
					style = ComposeResources.bodyStyle
				)
			}
		}

		// Lines
		Column(Modifier.padding(12.dp, 10.dp, 12.dp)) {
			for (quest in goalCard.quests) {
				GoalsPageQuestCardLine(quest)
			}
		}
	}
}

@Composable
fun GoalsPageQuestCardLine(quest: QuestsViewController.Quest) {
	Row(Modifier.height(83.dp).padding(start = 12.dp, end = 9.dp)) {
		// Description and progress
		Column(Modifier.weight(1f).padding(top = 13.dp)) {
			// Description
			Text(
				text = quest.quest.description,
				color = Color(0xFFC0ECFF),
				style = ComposeResources.bodyStyle
			)
			// Progress
			val (completion, max) = getQuestCompletion(quest.quest)
			val ratio = completion / max.toFloat()
			Row(
				horizontalArrangement = Arrangement.spacedBy(10.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				// Progress bar
				LinearProgressIndicator(
					progress = ratio,
					modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
					color = Color(0xFF33EEFF),
					backgroundColor = Color(0x33001166)
				)
				// Progress text
				Text(
					text = buildAnnotatedString {
						withStyle(SpanStyle(Color.White)) {
							append("%,d".format(completion))
						}
						append("   / %,d".format(max))
					},
					modifier = Modifier.width(80.dp),
					color = Color(0xFF85D6FF),
					textAlign = TextAlign.Right,
					style = ComposeResources.bodyStyle
				)
			}
		}
		// Reward
		GoalsRewards(quest.quest.questRewards.firstOrNull())
	}
}

@Composable
fun GoalsRewards(reward: QuestRewardContainer?) {
	Box(
		Modifier
			.padding(start = 18.dp)
			.width(62.dp),
		contentAlignment = Alignment.TopCenter
	) {
		if (reward == null) {
			return@Box
		}
		// Background decoration
		Box(Modifier.padding(top = 7.dp).size(58.dp).background(Color(0x59001166), CircleShape))
		// Icon
		Image(reward.icon!!.toPainter(), null, Modifier.size(64.dp))
		// Quantity
		val quantity = reward.quantity
		Text(
			text = if (quantity >= 1000) "%,dK".format(quantity / 1000) else if (quantity != 1) "%d".format(quantity) else "",
			modifier = Modifier
				.padding(top = 54.dp)
				.shear(-0.1f),
			color = Color.White,
			fontSize = 19.sp,
			style = ComposeResources.bodyStyle
		) // TODO Outline
	}
}

@Composable
fun AthenaChallengeListEntry_NpcQuest(quest: QuestsViewController.Quest) {
	// Container for overlays
	Box(
		modifier = Modifier.height(132.dp),
		contentAlignment = Alignment.CenterStart
	) {
		// Content
		Row(
			Modifier
				.height(120.dp)
				.clip(RoundedCornerShape(12.dp))
				.background(Color(0x7F001166))
		) {
			// Description and progress
			Column(
				modifier = Modifier
					.weight(1f)
					.fillMaxHeight()
					.padding(start = 32.dp, end = 16.dp),
				verticalArrangement = Arrangement.Center
			) {
				// Short description
				val shortDescription = quest.quest.defData.ShortDescription.format()
				if (!shortDescription.isNullOrEmpty()) {
					Text(
						text = shortDescription,
						modifier = Modifier.padding(bottom = 2.dp).shear(-0.1f),
						color = Color(0xFF80D4FF),
						style = ComposeResources.bodyStyle
					)
				}
				// Description
				Text(
					text = buildAnnotatedString {
						/*withStyle(SpanStyle(fontWeight = FontWeight.Black)) { TODO Stages
							append("Stage %,d of %,d - ".format(1, 3))
						}*/
						append(quest.quest.description)
					},
					color = Color(0xFFC0ECFF),
					style = ComposeResources.bodyStyle
				)
				// Progress
				Row(Modifier.padding(top = 5.dp)) {
					// Time remaining
					Row(
						Modifier.alpha(0f), // TODO Timer
						verticalAlignment = Alignment.CenterVertically
					) {
						Box(Modifier.size(24.dp, 24.dp).background(color = Color(0xFFEEEEEE)))
						Text(
							text = "4h 20m".uppercase(),
							color = Color.White,
							style = ComposeResources.bodyStyle
						)
					}
					Spacer(Modifier.weight(1f))
					val (completion, max) = getQuestCompletion(quest.quest)
					// Progress text
					Text(
						text = buildAnnotatedString {
							withStyle(SpanStyle(Color.White)) {
								append("%,d".format(completion))
							}
							append(" / %,d".format(max))
						},
						color = Color(0xFF80D4FF),
						textAlign = TextAlign.Right,
						style = ComposeResources.bodyStyle
					)
				}
			}
			// Reward
			AthenaChallengeRewards(quest.quest.questRewards.firstOrNull())
		}
	}
}

@Composable
fun AthenaChallengeRewards(reward: QuestRewardContainer?) {
	Column(
		modifier = Modifier
			.width(86.dp)
			.fillMaxHeight()
			.background(Color(0x7F001166))
			.padding(10.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		if (reward == null) {
			return@Column
		}
		// Icon
		Image(reward.icon!!.toPainter(), null, Modifier.size(64.dp))
		// Quantity
		val quantity = reward.quantity
		Text(
			text = if (quantity >= 1000) "%,dK".format(quantity / 1000) else if (quantity != 1) "%d".format(quantity) else "",
			modifier = Modifier.shear(-0.1f),
			color = Color.White,
			style = ComposeResources.bodyStyle
		)
	}
}

@Composable
fun Header(title: String) {
	Box(Modifier.height(63.dp), Alignment.CenterStart) {
		Text(
			text = title.uppercase(),
			style = ComposeResources.headingStyle,
			modifier = Modifier.shear(-0.2f),
			color = Color.White
		)
	}
}

fun QuestsViewController.createImage() = renderCompose {
	Row(Modifier.blueGradientBackground().padding(41.dp, 29.dp), horizontalArrangement = Arrangement.spacedBy(41.dp)) {
		for (category in categories) {
			QuestList(category, Modifier.width(946.dp))
		}
	}
}

fun QuestsViewController.QuestCategory.createImage(density: Float = 1f) = renderCompose(density) { QuestList2(this) }

fun QuestsViewController.QuestCategory.testWindow() {
	application {
		Window(
			onCloseRequest = ::exitApplication,
			title = "Quests",
			state = rememberWindowState(width = 300.dp, height = 300.dp)
		) {
			val scrollState = rememberScrollState()
			QuestList(this@testWindow, Modifier.blueGradientBackground().verticalScroll(scrollState))
		}
	}
}

fun Modifier.blueGradientBackground() = drawWithContent {
	drawRect(Brush.radialGradient(listOf(Color(0xFF099AFE), Color(0xFF0942B4)), radius = max(size.width, size.height) * 0.6f))
	drawContent()
}