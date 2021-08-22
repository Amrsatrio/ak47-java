package com.tb24.discordbot.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.fn.EpicApi
import com.tb24.fn.ProfileManager
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.assetdata.AthenaSeasonItemData
import com.tb24.fn.model.assetdata.AthenaSeasonItemDefinition
import com.tb24.fn.model.friends.FriendV2
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.CatalogOffer.CatalogItemPrice
import com.tb24.fn.model.gamesubcatalog.EStoreCurrencyType
import com.tb24.fn.model.mcpprofile.ProfileUpdate
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.util.*
import com.tb24.uasset.AssetManager
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.fort.exports.FortRarityData
import me.fungames.jfortniteparse.fort.exports.FortWorkerType
import me.fungames.jfortniteparse.fort.objects.FortColorPalette
import me.fungames.jfortniteparse.fort.objects.FortItemQuantityPair
import me.fungames.jfortniteparse.fort.objects.rows.FortQuestRewardTableRow
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Emote
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import okhttp3.HttpUrl
import okhttp3.Request
import retrofit2.Call
import retrofit2.Response
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.DateFormat
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import kotlin.math.abs

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

inline fun <reified T> okhttp3.Response.to(): T = body()!!.charStream().use { EpicApi.GSON.fromJson(it, T::class.java) }

val CommandContext<*>.commandName: String get() = nodes.first().node.name

@JvmField
val DF = DateFormat.getDateTimeInstance()

inline fun Date.format(): String = DF.format(this)

fun FortItemStack.render(displayQty: Int = quantity): String {
	var dn = displayName
	if (dn.isEmpty() && defData is FortWorkerType) {
		val asWorker = defData as FortWorkerType
		dn = defData.Rarity.rarityName.format() + ' ' + if (asWorker.bIsManager) "Lead Survivor" else "Survivor"
	}
	if (dn.isEmpty()) {
		dn = templateId
	}
	return (if (displayQty > 1) Formatters.num.format(displayQty) + " \u00d7 " else "") + dn
}

fun FortItemStack.renderWithIcon(displayQty: Int = quantity, bypassWhitelist: Boolean = false): String {
	transformedDefData // resolves this item if it is FortConditionalResourceItemDefinition
	return (getItemIconEmoji(this, bypassWhitelist)?.run { "$asMention " } ?: "") + render(displayQty)
}

fun CatalogItemPrice.icon(): String = when (currencyType) {
	EStoreCurrencyType.MtxCurrency -> Utils.MTX_EMOJI
	EStoreCurrencyType.GameItem -> getItemIconEmoji(FortItemStack(currencySubType, 1))?.asMention ?: currencySubType
	else -> currencyType.name
}

fun CatalogItemPrice.emote(): Emote? = when (currencyType) {
	EStoreCurrencyType.MtxCurrency -> DiscordBot.instance.discord.getEmoteById(751101530626588713L)
	EStoreCurrencyType.GameItem -> getItemIconEmoji(FortItemStack(currencySubType, 1))
	else -> null
}

fun CatalogItemPrice.render(quantity: Int = 1) = icon() + ' ' + (if (basePrice != -1) Formatters.num.format(quantity * basePrice) else "<unresolved>") + if (regularPrice != basePrice) " ~~${Formatters.num.format(quantity * regularPrice)}~~" else ""

fun CatalogItemPrice.getAccountBalance(profileManager: ProfileManager): Int {
	if (currencyType == EStoreCurrencyType.MtxCurrency) {
		return countMtxCurrency(profileManager.getProfileData("common_core"))
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

fun FortItemQuantityPair.render(fac: Float, conditionalCondition: Boolean) =
	asItemStack().apply { setConditionForConditionalItem(conditionalCondition) }.renderWithIcon((Quantity * fac).toInt())

fun Map<FName, FortQuestRewardTableRow>.render(prefix: String, orPrefix: String, fac: Float, bold: Boolean, conditionalCondition: Boolean): List<String> {
	val fmt = if (bold) "**" else ""
	val lines = mutableListOf<String>()
	var lastIsSelectable = false
	toSortedMap { o1, o2 ->
		val priority1 = o1.text.substringAfterLast('_', "0").toInt()
		val priority2 = o2.text.substringAfterLast('_', "0").toInt()
		priority1 - priority2
	}.forEach {
		lines.add("$prefix$fmt${it.value.asItemStack().apply { setConditionForConditionalItem(conditionalCondition) }.renderWithIcon((it.value.Quantity * fac).toInt())}$fmt")
		if (lastIsSelectable && it.value.Selectable) {
			lines.add("$orPrefix- OR -")
		}
		lastIsSelectable = it.value.Selectable
	}
	return lines
}

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
fun Message.yesNoReactions(author: User, inTime: Long = 45000L): CompletableFuture<Boolean> = CompletableFuture.supplyAsync {
	val icons = arrayOf("✅", "❌").apply { forEach { addReaction(it).queue() } }
	awaitReactions({ reaction, user, _ -> icons.contains(reaction.reactionEmote.name) && user?.idLong == author.idLong }, AwaitReactionsOptions().apply {
		max = 1
		time = inTime
		errors = arrayOf(CollectorEndReason.TIME)
	}).await().first().reactionEmote.name == "✅"
}

fun Message.awaitOneReaction(source: CommandSourceStack) =
	awaitReactions({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
		max = 1
		time = 30000
		errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
	}).await().first().reactionEmote.name

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

fun String?.escapeMarkdown() = if (this == null) null else replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("~", "\\~")

fun Array<FriendV2>.sortedFriends() = sortedBy { (if (!it.alias.isNullOrEmpty()) it.alias else if (!it.displayName.isNullOrEmpty()) it.displayName else it.accountId).toLowerCase() }

inline fun CatalogOffer.holder() = CatalogEntryHolder(this)

inline fun String?.orDash() = if (isNullOrEmpty()) "\u2014" else this

inline fun Date.renderWithRelative() = "${format()} (${relativeFromNow()})"

inline fun Date.relativeFromNow(withSeconds: Boolean = false) = time.relativeFromNow(withSeconds)

fun Long.relativeFromNow(withSeconds: Boolean = false): String {
	val delta = System.currentTimeMillis() - this
	val elapsedStr = StringUtil.formatElapsedTime(abs(delta), withSeconds).toString()
	return when {
		delta < 0L -> "in $elapsedStr"
		delta < 60L -> "just now"
		else /*delta > 0L*/ -> "$elapsedStr ago"
	}
}

fun String.shortenUrl(source: CommandSourceStack): String {
	val cuttlyApiKey = "2f305deea48f34be34018ab54b7b7dd2b72e4"
	val shortenerUrl = HttpUrl.get("https://cutt.ly/api/api.php").newBuilder().addQueryParameter("key", cuttlyApiKey).addQueryParameter("short", this).build()
	val shortenerResponse = source.api.okHttpClient.newCall(Request.Builder().url(shortenerUrl).build()).exec().to<JsonObject>().getAsJsonObject("url")
	return shortenerResponse.getString("shortLink")!!
}

inline fun <T> Iterable<T>.search(query: String, minimumSimilarity: Float = .33f, extractor: (T) -> String = { it.toString() }): T? {
	val query = query.toLowerCase()
	var maxSim = minimumSimilarity
	var result: T? = null
	for (item in this) {
		val key = extractor(item).toLowerCase()
		if (key == query) {
			return item
		}
		val sim = similarity(key, query)
		//println("sim $key, $query = $sim")
		if (sim > maxSim) {
			maxSim = sim
			result = item
		}
	}
	return result
}

fun similarity(s1: String, s2: String): Float {
	var longer = s1
	var shorter = s2
	if (s1.length < s2.length) {
		longer = s2
		shorter = s1
	}
	val longerLength = longer.length
	if (longerLength == 0) {
		return 1f
	}
	return (longerLength - Utils.damerauLevenshteinDistance(longer, shorter)) / longerLength.toFloat()
}

inline fun <reified T : AthenaSeasonItemData> AthenaSeasonItemDefinition.getAdditionalDataOfType() = AdditionalSeasonData?.firstOrNull { it.value is T }?.value as T?

val AthenaProfileStats.purchasedBpOffers get() = (purchased_bp_offers as? JsonArray)?.let { EpicApi.GSON.fromJson(it, Array<AthenaProfileStats.BattlePassOfferPurchaseRecord>::class.java).associateBy { it.offerId } } ?: emptyMap()

fun FortRarityData.forRarity(rarity: EFortRarity): FortColorPalette {
	val r = RarityCollection[rarity.ordinal]
	return FortColorPalette().apply {
		Color1 = r.Color1
		Color2 = r.Color2
		Color3 = r.Color3
		Color4 = r.Color4
		Color5 = r.Color5
	}
}

fun Number.awtColor(hasAlpha: Boolean = toInt() ushr 24 != 0) = Color(toInt(), hasAlpha)

inline fun createAndDrawCanvas(w: Int, h: Int, draw: (ctx: Graphics2D) -> Unit): BufferedImage {
	val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
	val ctx = canvas.createGraphics()
	ctx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
	ctx.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
	ctx.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
	draw(ctx)
	ctx.dispose()
	return canvas
}

object ResourcesContext {
	val burbankSmallBold by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/burbanksmall-bold.ufont") }
	val burbankSmallBlack by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/burbanksmall-black.ufont") }
	val burbankBigRegularBold by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigRegular-Bold.ufont") }
	val burbankBigRegularBlack by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigRegular-Black.ufont") }
	val burbankBigCondensedBlack by lazy { fromPaks("FortniteGame/Content/UI/Foundation/Fonts/BurbankBigCondensed-Black.ufont") }

	private fun fromPaks(path: String) = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(AssetManager.INSTANCE.saveGameFile(path)))
}