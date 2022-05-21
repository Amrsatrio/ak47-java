package com.tb24.discordbot.images

import com.tb24.discordbot.commands.rarityData
import com.tb24.discordbot.commands.rewardsTableCache
import com.tb24.discordbot.util.awtColor
import com.tb24.discordbot.util.createAndDrawCanvas
import com.tb24.discordbot.util.forRarity
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.asItemStack
import com.tb24.fn.util.format
import com.tb24.fn.util.getPathName
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.fort.exports.FortChallengeBundleItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortQuestItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortTandemCharacterData
import me.fungames.jfortniteparse.fort.objects.FortColorPalette
import me.fungames.jfortniteparse.fort.objects.FortItemQuantityPair
import me.fungames.jfortniteparse.fort.objects.rows.FortQuestRewardTableRow
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.ue4.locres.Locres
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import me.fungames.jfortniteparse.util.drawCenteredString
import me.fungames.jfortniteparse.util.toPngArray
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.GeneralPath
import java.awt.image.BufferedImage
import java.io.File
import java.text.AttributedString
import javax.imageio.ImageIO
import kotlin.jvm.internal.Ref.ObjectRef
import kotlin.system.exitProcess

fun FortChallengeBundleItemDefinition.createChallengeBundleContainer(): ChallengeBundleContainer {
	val container = ChallengeBundleContainer()
	for (questInfo in QuestInfos) {
		var q: QuestContainer
		var quest = questInfo.QuestDefinition.load<FortQuestItemDefinition>()
		while (true) {
			val questDef = quest ?: break
			q = QuestContainer(questDef)
			container.quests.add(q)
			quest = q.nextQuest ?: break
		}
	}
	BundleCompletionRewards?.forEach { completionReward ->
		for (reward in completionReward.Rewards) {

		}
	}
	return container
}

class ChallengeBundleContainer {
	val quests = mutableListOf<QuestContainer>()

	fun getImage(locres: Locres?): BufferedImage {
		return createAndDrawCanvas(1000, quests.size * QuestContainer.HEIGHT) { ctx ->
			var cur = 0
			quests.forEach {
				val kek = it.getImage(locres)
				ctx.drawImage(kek, 0, cur, null)
				cur += QuestContainer.HEIGHT
			}
		}
	}
}

class QuestContainer {
	companion object {
		const val HEIGHT = 240
	}

	var palette: FortColorPalette
	var tandem: FortTandemCharacterData?
	var title: FText?
	var shortDescription: FText?
	var completionText: FText?
	var rewards = mutableListOf<QuestRewardContainer>()
	var completion: Int
	var max: Int
	var objectName: String
	var nextQuest: FortQuestItemDefinition? = null

	constructor(quest: FortQuestItemDefinition) {
		palette = quest.palette()
		tandem = quest.TandemCharacterData?.load<FortTandemCharacterData>()
		title = quest.DisplayName
		//if (quest.Description.text == title.text) {
		//	Man idk how to handle quests with title
		//}
		shortDescription = quest.ShortDescription
		completionText = quest.CompletionText
		val nextQuest = ObjectRef<FortQuestItemDefinition?>()
		quest.getRewards(rewards, nextQuest)
		this.nextQuest = nextQuest.element
		completion = 0
		max = getQuestMax(quest)

		objectName = quest.name
	}

	fun getImage(locres: Locres?): BufferedImage {
		val w = 1000
		val h = HEIGHT
		val scale = 1f
		return createAndDrawCanvas(w, h) { ctx ->
			ctx.color = 0x0C151C.awtColor()
			ctx.fillRect(0, 0, w, h)

			// tandem avatar
			val imgSz = HEIGHT
			// > background
			val tandemBg = ImageIO.read(File("canvas/bg_tandem.png"))
			ctx.drawImage(tandemBg, 0, 0, imgSz, imgSz, null)
			// > the avatar
			val tandemPic = (tandem?.EntryListIcon?.load<UTexture2D>() ?: loadObject<UTexture2D>("/Game/Athena/HUD/Quests/Art/T_NPC_Default.T_NPC_Default")!!).toBufferedImage()
			ctx.drawImage(tandemPic, 0, 0, imgSz, imgSz, null)
			// > overlay gradient
			ctx.paint = LinearGradientPaint(0f, 0f, 0f, imgSz.toFloat(), floatArrayOf(.7f, 1f), arrayOf(0x0055D0.awtColor(true), 0x7F0055D0.awtColor()))
			ctx.fillRect(0, 0, imgSz, imgSz)

			val pad = 16
			val contentLeft = HEIGHT + pad + 12
			var cur = pad

			// display name
			ctx.color = Color.WHITE
			ctx.font = f
			ctx.drawString(title?.textForLocres(locres) ?: objectName, contentLeft, cur + ctx.fontMetrics.ascent)
			cur += ctx.fontMetrics.height + 4
			val belowDisplayName = cur

			val wrappingWidth = w - contentLeft - pad

			// short description
			shortDescription?.let {
				ctx.color = palette.Color2.toColor()
				cur += ctx.para(it.textForLocres(locres), contentLeft, cur, wrappingWidth)
			}

			// completion text
			completionText?.let {
				cur += ctx.para("> " + it.textForLocres(locres), contentLeft, cur, wrappingWidth)
			}

			// object name
			//ctx.drawString(objectName, contentLeft, cur + ctx.fontMetrics.ascent)
			//cur += ctx.fontMetrics.height
			// draw this? or not? idk again

			ctx.font = f

			// rewards
			for (reward in rewards) {
				val rh = 64
				reward.drawHorizontal(ctx, contentLeft, cur, rh)
				cur += rh
			}

			// completion
			val completionTextRaw = "%,d / %,d".format(completion, max)
			val completionText = AttributedString(completionTextRaw)
			completionText.addAttribute(TextAttribute.FONT, ctx.font)
			completionText.addAttribute(TextAttribute.FOREGROUND, palette.Color2.toColor(), completionTextRaw.indexOf('/'), completionTextRaw.length)
			val completionTextLayout = TextLayout(completionText.iterator, ctx.fontRenderContext)
			ctx.color = Color.WHITE
			completionTextLayout.draw(ctx, (w - 32 - completionTextLayout.bounds.width).toFloat(), (h - 16).toFloat())

			// separator effect
			ctx.color = palette.Color2.toColor()
			val before = ctx.transform
			ctx.translate(HEIGHT - 9, 0)
			ctx.fill(kekz(18, HEIGHT, ltx = 0.2f, rbx = 0.4f))
			ctx.transform = before
		}
	}
}

private fun FortItemDefinition.palette(): FortColorPalette {
	var palette = rarityData.forRarity(Rarity ?: EFortRarity.Uncommon)
	Series?.value?.also {
		palette = it.Colors
	}
	return palette
}

private fun qtxt(dn: String, qty: Int = 1): String {
	return if (qty == 1) dn else "%,d %s".format(qty, dn)
}

fun Graphics2D.para(text: String, x: Int, y: Int, wrappingWidth: Int): Int {
	val attributedString = AttributedString(text)
	attributedString.addAttribute(TextAttribute.FONT, f.deriveFont(20f))
	val measurer = LineBreakMeasurer(attributedString.iterator, fontRenderContext)
	var dy = 0

	while (measurer.position < text.length) {
		val layout = measurer.nextLayout(wrappingWidth.toFloat())
		dy += layout.ascent.toInt()
		val dx = if (layout.isLeftToRight) 0f else wrappingWidth - layout.advance
		layout.draw(this, x + dx, y + dy.toFloat())
		dy += (layout.descent + layout.leading).toInt()
	}

	return dy
}

fun kekz(
	w: Int, h: Int,
	ltx: Float = 0f,
	lty: Float = 0f,
	rtx: Float = 0f,
	rty: Float = 0f,
	rbx: Float = 0f,
	rby: Float = 0f,
	lbx: Float = 0f,
	lby: Float = 0f
): GeneralPath {
	val path = GeneralPath()
	val ltx = ltx * w
	val lty = lty * h
	val rtx = rtx * w
	val rty = rty * h
	val rbx = rbx * w
	val rby = rby * h
	val lbx = lbx * w
	val lby = lby * h

	path.reset()
	path.moveTo(ltx, lty)
	path.lineTo(w - rtx, rty)
	path.lineTo(w - rbx, h - rby)
	path.lineTo(lbx, h - lby)
	path.closePath()

	return path
}

val f = Font.createFont(Font.TRUETYPE_FONT, File("C:\\Users\\satri\\AppData\\Local\\Microsoft\\Windows\\Fonts\\zh-cn.ttf")).deriveFont(24f)

class QuestRewardContainer {
	var palette: FortColorPalette? = null
	var icon: BufferedImage? = null
	var itemName: FText? = null
	var quantity = 0
	var or = false

	constructor(reward: FortItemQuantityPair) {
		val item = reward.asItemStack()
		val def = item.defData
		palette = def.palette()
		icon = item.getPreviewImagePath()?.load<UTexture2D>()?.toBufferedImage()
		itemName = FText(item.displayName) // todo pls item's display name as ftext
		quantity = reward.Quantity
	}

	constructor(reward: FortQuestRewardTableRow) {
		val item = reward.asItemStack()
		val def = item.defData
		palette = def.palette()
		icon = item.getPreviewImagePath()?.load<UTexture2D>()?.toBufferedImage()
		itemName = FText(item.displayName) // todo pls item's display name as ftext
		quantity = reward.Quantity
	}

	constructor() {
		or = true
	}

	fun draw(ctx: Graphics2D, x: Int, y: Int, size: Int) {
		ctx.color = Color.WHITE
		if (!or) {
			icon?.let {
				ctx.drawImage(it, x, y, size, size, null)
				//ctx.drawImage(it, x, y, )
			}
			val quantityText = "%,d".format(quantity)
			ctx.drawCenteredString(quantityText, x + size / 2, y + size - ctx.fontMetrics.height + ctx.fontMetrics.ascent)

		} else {
			ctx.drawCenteredString("- OR -", x + size / 2, y)
		}
	}

	fun drawHorizontal(ctx: Graphics2D, x: Int, y: Int, h: Int) {
		// icon
		ctx.drawImage(icon, x, y, h, h, null)

		// [quantity ]display name
		ctx.font = f
		ctx.color = palette?.Color1?.toColor() ?: Color.WHITE
		ctx.drawString(qtxt(itemName!!.format()!!, quantity), x + h + 16, y + (h - ctx.fontMetrics.height) / 2 + ctx.fontMetrics.ascent)
	}
}

fun getQuestMax(quest: FortQuestItemDefinition, allowCompletionCountOverride: Boolean = true): Int {
	var max = 0
	for (objective in quest.Objectives) {
		if (objective.bHidden) {
			continue
		}
		max += objective.Count
	}
	if (allowCompletionCountOverride && quest.ObjectiveCompletionCount != null) {
		max = quest.ObjectiveCompletionCount
	}
	return max
}

fun FortQuestItemDefinition.getRewards(outRewards: MutableList<QuestRewardContainer>, outNextQuest: ObjectRef<FortQuestItemDefinition?>? = null) {
	Rewards?.forEach { reward ->
		if (reward.ItemPrimaryAssetId.PrimaryAssetType.Name.text != "Quest") {
			outRewards.add(QuestRewardContainer(reward))
		} else {
			outNextQuest?.element = reward.asItemStack().defData as? FortQuestItemDefinition
		}
	}
	val rewardsTablePath = RewardsTable?.getPathName()
	val rewardsTableRewards = if (rewardsTablePath != null) {
		rewardsTableCache
			.getOrPut(rewardsTablePath) { RewardsTable.value.rows.mapValues { it.value.mapToClass(FortQuestRewardTableRow::class.java) } }
			.filter { it.value.QuestTemplateId == "*" || it.value.QuestTemplateId.substringAfter(':').equals(name, true) && !it.value.Hidden }
	} else null
	if (rewardsTableRewards != null) {
		var lastIsSelectable = false
		rewardsTableRewards.toSortedMap { o1, o2 ->
			val priority1 = o1.text.substringAfterLast('_', "0").toInt()
			val priority2 = o2.text.substringAfterLast('_', "0").toInt()
			priority1 - priority2
		}.forEach { (_, reward) ->
			outRewards.add(QuestRewardContainer(reward))
			if (lastIsSelectable && reward.Selectable) {
				outRewards.add(QuestRewardContainer())
			}
			lastIsSelectable = reward.Selectable
		}
	}
}

val FortQuestItemDefinition.rewards: List<QuestRewardContainer> get() = mutableListOf<QuestRewardContainer>().also(::getRewards)
val FortItemStack.questRewards get() = (defData as? FortQuestItemDefinition)?.rewards ?: emptyList()

fun main() {
	AssetManager.INSTANCE.loadPaks()
	var path = "/BattlepassS17/Items/QuestBundles/QuestBundle_S17_Legendary_Week_10"
	path = "/BattlepassS17/Items/QuestBundles/MissionBundle_S17_Week_10"
	File("testquest.png").writeBytes(loadObject<FortChallengeBundleItemDefinition>(path)!!.createChallengeBundleContainer().getImage(null).toPngArray())
	exitProcess(0)
}