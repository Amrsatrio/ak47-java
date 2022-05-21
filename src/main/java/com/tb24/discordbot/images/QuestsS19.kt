package com.tb24.discordbot.images

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tb24.discordbot.commands.getQuestCompletion
import com.tb24.discordbot.ui.QuestsViewController
import com.tb24.discordbot.util.ComposeResources
import com.tb24.discordbot.util.description
import com.tb24.discordbot.util.renderCompose
import com.tb24.discordbot.util.shear
import com.tb24.fn.util.format
import kotlin.math.max

@Composable
fun QuestList(category: QuestsViewController.QuestCategory, modifier: Modifier = Modifier) {
	Column(
		modifier = modifier
			.drawWithContent {
				drawRect(Brush.radialGradient(listOf(Color(0xFF099AFE), Color(0xFF0942B4)), radius = max(size.width, size.height) / 2))
				drawContent()
			}
			.padding(41.dp, 29.dp),
		verticalArrangement = Arrangement.spacedBy(7.dp)
	) {
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
				Box(
					Modifier
						.weight(1f)
						.height(8.dp)
						.clip(RoundedCornerShape(4.dp))
						.background(Color(0x33001166))
				) {
					Box(
						Modifier
							.fillMaxWidth(ratio)
							.fillMaxHeight()
							.background(Color(0xFF33EEFF))
					)
				}
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

fun QuestsViewController.QuestCategory.createImage(modifier: Modifier = Modifier) = renderCompose {
	QuestList(this, modifier.width(946.dp))
}

fun QuestsViewController.QuestCategory.testWindow() {
	application {
		Window(
			onCloseRequest = ::exitApplication,
			title = "Quests",
			state = rememberWindowState(width = 300.dp, height = 300.dp)
		) {
			val scrollState = rememberScrollState()
			QuestList(this@testWindow, Modifier.verticalScroll(scrollState))
		}
	}
}