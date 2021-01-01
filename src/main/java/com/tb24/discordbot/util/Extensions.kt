package com.tb24.discordbot.util

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.HttpException
import com.tb24.fn.EpicApi
import com.tb24.fn.ProfileManager
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.CatalogOffer.CatalogItemPrice
import com.tb24.fn.model.gamesubcatalog.EStoreCurrencyType
import com.tb24.fn.model.mcpprofile.ProfileUpdate
import com.tb24.fn.util.CatalogHelper
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.AssetManager
import me.fungames.jfortniteparse.fort.exports.FortWorkerType
import me.fungames.jfortniteparse.fort.objects.rows.FortQuestRewardTableRow
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.*
import retrofit2.Call
import retrofit2.Response
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.DateFormat
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import javax.imageio.ImageIO
import kotlin.math.min

val WHITELIST_ICON_EMOJI_ITEM_TYPES = arrayOf("AccountResource", "ConsumableAccountItem", "Currency", "Stat")

@Throws(HttpException::class, IOException::class)
fun ProfileManager.dispatchClientCommandRequest(payload: Any, profileId: String = "common_core"): CompletableFuture<ProfileUpdate> =
	CompletableFuture.supplyAsync { makeClientCommandCall(payload.javaClass.simpleName, profileId, payload).exec().body().apply { handleProfileUpdate(this) } }

@Throws(HttpException::class, IOException::class)
fun ProfileManager.dispatchPublicCommandRequest(user: GameProfile, payload: Any, profileId: String = "common_core"): CompletableFuture<ProfileUpdate> =
	CompletableFuture.supplyAsync {
		val profileGroup = getProfileGroup(user.id)
		profileGroup.owner = user
		makePublicCommandCall(profileGroup, payload.javaClass.simpleName, profileId, payload).exec().body().apply {
			profileGroup.handleProfileUpdate(this)
		}
	}

@Throws(HttpException::class, IOException::class)
fun <T> Call<T>.exec(): Response<T> = execute().apply {
	if (!isSuccessful)
		throw HttpException(this)
}

@Throws(HttpException::class, IOException::class)
fun <T> Call<T>.future(): CompletableFuture<Response<T>> = CompletableFuture.supplyAsync {
	execute().apply {
		if (!isSuccessful)
			throw HttpException(this)
	}
}

@Throws(HttpException::class, IOException::class)
fun okhttp3.Call.exec(): okhttp3.Response = execute().apply {
	if (!isSuccessful)
		throw HttpException(this)
}

inline fun <reified T> okhttp3.Response.to(): T = body()!!.run { EpicApi.GSON.fromJson(charStream(), T::class.java) }.apply { close() }

val CommandContext<*>.commandName: String get() = nodes.first().node.name

private val DF = DateFormat.getDateTimeInstance()

fun Date.format(): String = DF.format(this)

fun FortItemStack.render(displayQty: Int = quantity): String {
	var dn = displayName
	if (dn.isEmpty() && defData is FortWorkerType) {
		val asWorker = defData as FortWorkerType
		dn = defData.Rarity.rarityName.format() + ' ' + if (asWorker.bIsManager) "Lead Survivor" else "Survivor"
	}
	return (if (displayQty > 1) Formatters.num.format(displayQty) + " \u00d7 " else "") + dn
}

fun FortItemStack.renderWithIcon(displayQty: Int = quantity): String {
	transformedDefData // resolves this item if it is FortConditionalResourceItemDefinition
	return (getItemIconEmoji(templateId)?.run { "$asMention " } ?: "") + render(displayQty)
}

fun CatalogItemPrice.icon(): String = when (currencyType) {
	EStoreCurrencyType.MtxCurrency -> Utils.MTX_EMOJI
	EStoreCurrencyType.GameItem -> getItemIconEmoji(currencySubType)?.asMention ?: currencySubType
	else -> currencyType.name
}

fun CatalogItemPrice.emote(): Emote? = when (currencyType) {
	EStoreCurrencyType.MtxCurrency -> DiscordBot.instance.discord.getEmoteById(751101530626588713L)
	EStoreCurrencyType.GameItem -> getItemIconEmoji(currencySubType)
	else -> null
}

@Synchronized
fun getItemIconEmoji(templateId: String, createIfNonexistent: Boolean = DiscordBot.ENV == "test"): Emote? {
	if (templateId.toLowerCase().contains(":mtx")) {
		return DiscordBot.instance.discord.getEmoteById(Utils.MTX_EMOJI_ID)
	}
	val split = templateId.split(":")
	val type = split[0]
	val name = split[1]
	if (type !in WHITELIST_ICON_EMOJI_ITEM_TYPES) {
		return null
	}
	val existing = DiscordBot.instance.discord.getEmotesByName(name.run { substring(0, min(32, length)) }, true).firstOrNull()
	if (existing != null) {
		return existing
	}
	if (!createIfNonexistent) {
		return null
	}
	val item = FortItemStack(templateId, 1)
	val defData = item.transformedDefData ?: return null
	val icon = item.getPreviewImagePath(true)?.load<UTexture2D>()?.toBufferedImage()
	val baos = ByteArrayOutputStream()
	ImageIO.write(icon, "png", baos)
	return DiscordBot.instance.discord.getGuildById(648556726672556048L)?.createEmote(defData.name.run { substring(0, min(32, length)) }, Icon.from(baos.toByteArray(), Icon.IconType.PNG))?.complete()
}

fun CatalogItemPrice.render(quantity: Int = 1) = icon() + ' ' + Formatters.num.format(quantity * basePrice) + if (saleType != null) " ~~${Formatters.num.format(quantity * regularPrice)}~~" else ""

fun CatalogItemPrice.getAccountBalance(profileManager: ProfileManager): Int {
	if (currencyType == EStoreCurrencyType.MtxCurrency) {
		return CatalogHelper.countMtxCurrency(profileManager.getProfileData("common_core"))
	} else if (currencyType == EStoreCurrencyType.GameItem) {
		for (profile in profileManager.profiles.values) {
			for (item in profile.items.values) {
				if (item.templateId == currencySubType && item.quantity > 0) {
					return item.quantity
				}
			}
		}
	}
	return 0
}

fun CatalogItemPrice.getAccountBalanceText(profileManager: ProfileManager) = icon() + ' ' + Formatters.num.format(getAccountBalance(profileManager))

fun Map<FName, FortQuestRewardTableRow>.render(prefix: String, fac: Float = 1f, bold: Boolean = false, conditionalCondition: Boolean): String {
	val fmt = if (bold) "**" else ""
	val lines = arrayListOf<String>()
	var lastEntry: FortQuestRewardTableRow? = null
	toSortedMap { o1, o2 ->
		val priority1 = o1.text.substringAfterLast('_', "0").toInt()
		val priority2 = o2.text.substringAfterLast('_', "0").toInt()
		priority1 - priority2
	}.forEach {
		lines.add("$prefix$fmt${it.value.asItemStack().apply { setConditionForConditionalItem(conditionalCondition) }.renderWithIcon((it.value.Quantity * fac).toInt())}$fmt")
		if (lastEntry != null && lastEntry!!.Selectable && it.value.Selectable) {
			lines.add("$prefix- OR -")
		}
		lastEntry = it.value
	}
	return lines.joinToString("\n")
}

fun FortQuestRewardTableRow.asItemStack() = FortItemStack(TemplateId.text, Quantity)

@Throws(CommandSyntaxException::class)
fun <T> List<T>.safeGetOneIndexed(index: Int, reader: StringReader? = null, start: Int = 0): T {
	if (index < 1) {
		val type = CommandSyntaxException.BUILT_IN_EXCEPTIONS.integerTooLow()
		throw if (reader != null) type.createWithContext(reader.apply { cursor = start }, index, 1) else type.create(index, 1)
	} else if (index > size) {
		val type = CommandSyntaxException.BUILT_IN_EXCEPTIONS.integerTooHigh()
		throw if (reader != null) type.createWithContext(reader.apply { cursor = start }, index, size) else type.create(index, size)
	}
	return get(index - 1)
}

@Throws(CommandSyntaxException::class)
fun <T> Array<T>.safeGetOneIndexed(index: Int, reader: StringReader? = null, start: Int = 0): T {
	if (index < 1) {
		val type = CommandSyntaxException.BUILT_IN_EXCEPTIONS.integerTooLow()
		throw if (reader != null) type.createWithContext(reader.apply { cursor = start }, index, 1) else type.create(index, 1)
	} else if (index > size) {
		val type = CommandSyntaxException.BUILT_IN_EXCEPTIONS.integerTooHigh()
		throw if (reader != null) type.createWithContext(reader.apply { cursor = start }, index, size) else type.create(index, size)
	}
	return get(index - 1)
}

@Throws(CommandSyntaxException::class)
fun Message.yesNoReactions(author: User, inTime: Long = 30000L): CompletableFuture<Boolean> = CompletableFuture.supplyAsync {
	try {
		val icons = arrayOf("✅", "❌").apply { forEach { addReaction(it).queue() } }
		awaitReactions({ reaction, user, _ -> icons.contains(reaction.reactionEmote.name) && user?.idLong == author.idLong }, AwaitReactionsOptions().apply {
			max = 1
			time = inTime
			errors = arrayOf(CollectorEndReason.TIME)
		}).await().first().reactionEmote.name == "✅"
	} catch (e: CollectorException) {
		throw SimpleCommandExceptionType(LiteralMessage("Timed out while waiting for your confirmation.")).create()
	}
}

fun <T> Future<T>.await(): T {
	try {
		return get()
	} catch (e: ExecutionException) {
		throw e.cause!!
	}
}

fun <T> EmbedBuilder.addFieldSeparate(title: String, entries: Collection<T>?, bullet: Int = 2, inline: Boolean = false, mapper: ((T) -> String)? = null): EmbedBuilder {
	if (entries == null || entries.isEmpty()) {
		addField(title, "No entries", inline)
		return this
	}
	var buffer = ""
	var fieldCount = 0
	for ((i, entry) in entries.withIndex()) {
		val s = (when (bullet) {
			1 -> "\u2022 "
			2 -> "${Formatters.num.format(i + 1)}. "
			else -> ""
		}) + if (mapper != null) mapper(entry) else entry.toString()
		val separator = if (i > 0) "\n" else ""
		if (buffer.length + separator.length + s.length <= MessageEmbed.VALUE_MAX_LENGTH) {
			buffer += separator + s
		} else {
			addField(title + if (fieldCount > 0) " (continued)" else "", buffer, inline)
			++fieldCount
			buffer = s
		}
	}
	if (buffer.isNotEmpty()) {
		addField(title + if (fieldCount > 0) " (continued)" else "", buffer, inline)
	}
	return this
}

inline fun CatalogOffer.holder() = CatalogEntryHolder(this)

fun String?.orDash() = this?.takeIf { it.isNotEmpty() } ?: "\u2014"

fun Number.awtColor(hasAlpha: Boolean = toInt() ushr 24 != 0) = Color(toInt(), hasAlpha)

inline fun createAndDrawCanvas(w: Int, h: Int, draw: (ctx: Graphics2D) -> Unit): BufferedImage {
	val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
	val ctx = canvas.createGraphics()
	ctx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
	ctx.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
	ctx.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
	draw(ctx)
	return canvas
}

object ResourcesContext {
	val burbankSmallBold by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/burbanksmall-bold.ufont") }
	val burbankSmallBlack by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/burbanksmall-black.ufont") }
	val burbankBigRegularBold by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigRegular-Bold.ufont") }
	val burbankBigRegularBlack by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigRegular-Black.ufont") }
	val burbankBigCondensedBlack by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigCondensed-Black.ufont") }

	private inline fun fromPaks(path: String) = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(AssetManager.INSTANCE.provider.saveGameFile(path)))
}