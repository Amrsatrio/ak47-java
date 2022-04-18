package com.tb24.discordbot.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.L10N
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.commands.rarityData
import com.tb24.discordbot.item.ItemTypeResolver
import com.tb24.discordbot.item.ItemUtils
import com.tb24.discordbot.managers.managerData
import com.tb24.fn.EpicApi
import com.tb24.fn.ProfileManager
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.account.Token
import com.tb24.fn.model.assetdata.AthenaSeasonItemData
import com.tb24.fn.model.assetdata.AthenaSeasonItemDefinition
import com.tb24.fn.model.friends.FriendV2
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.CatalogOffer.CatalogItemPrice
import com.tb24.fn.model.gamesubcatalog.EStoreCurrencyType
import com.tb24.fn.model.launcher.BuildResponse
import com.tb24.fn.model.mcpprofile.ProfileUpdate
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.util.*
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.fort.exports.*
import me.fungames.jfortniteparse.fort.objects.AthenaRewardItemReference
import me.fungames.jfortniteparse.fort.objects.FortColorPalette
import me.fungames.jfortniteparse.fort.objects.FortItemQuantityPair
import me.fungames.jfortniteparse.fort.objects.rows.CosmeticMarkupTagDataRow
import me.fungames.jfortniteparse.fort.objects.rows.CosmeticSetDataRow
import me.fungames.jfortniteparse.fort.objects.rows.FortQuestRewardTableRow
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.ComponentInteraction
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import net.dv8tion.jda.api.interactions.components.selections.SelectMenu
import net.dv8tion.jda.api.utils.TimeFormat
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.io.PrintWriter
import java.io.StringWriter
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import kotlin.math.min

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

inline fun <reified T> okhttp3.Response.to(): T = body!!.charStream().use { EpicApi.GSON.fromJson(it, T::class.java) }

inline fun <reified T> EpicApi.performWebApiRequest(url: String): T {
	val request = Request.Builder().url(url)
		.header("Cookie", "EPIC_BEARER_TOKEN=" + userToken.access_token)
		.build()
	val response = okHttpClient.newCall(request).exec()
	if (response.request.url.toString().contains("epicgames.com/id/logout")) {
		throw HttpException(response)
	}
	return response.to()
}

val CommandContext<*>.commandName: String get() = nodes.first().node.name

inline fun Date.format(): String = TimeFormat.DATE_TIME_SHORT.format(this.time)

val FortItemStack.palette get() = defData?.Series?.value?.Colors ?: rarityData.forRarity(rarity)

val FortItemStack.description: String get() {
	var description = transformedDefData?.Description.format() ?: ""
	if (defData is FortWorkerType) {
		description = TextFormatter.format(description, mapOf("Gender" to attributes.getString("gender", "1").toInt()))
		val personality = getSurvivorPersonalityText(attributes.getString("personality", ""))
		description = description.replace("[Worker.Personality]", (personality ?: L10N.InvalidPersonality).format()!!)
		description = description.replace("[Worker.SetBonus.Buff]", L10N.SetBonusBuff_Default.format()!!)
	}
	val defData = transformedDefData
	if (defData !is AthenaCosmeticItemDefinition) {
		return description
	}
	val sb = StringBuilder(description)
	if (defData.GameplayTags != null) {
		val cosmeticSets = loadObject<UDataTable>("/Game/Athena/Items/Cosmetics/Metadata/CosmeticSets")!!
		val cosmeticUserFacingTags = loadObject<UDataTable>("/Game/Athena/Items/Cosmetics/Metadata/CosmeticUserFacingTags")!!
		var seasonIntroduced = -1
		val flags = mutableListOf<CosmeticMarkupTagDataRow>()
		for (tag in defData.GameplayTags) {
			val s = tag.toString()
			if (s.startsWith("Cosmetics.Set.")) {
				val cosmeticSet = cosmeticSets.findRowMapped<CosmeticSetDataRow>(FName(s))
				sb.append(L10N.CosmeticItemDescription_SetMembership.format()!!.replace("<SetName>", "**").replace("</>", "**").replace("{0}", if (cosmeticSet != null) cosmeticSet.DisplayName.format()!! else s.substring("Cosmetics.Set.".length)))
			} else if (s.startsWith("Cosmetics.Filter.Season.")) {
				seasonIntroduced = s.substring("Cosmetics.Filter.Season.".length).toInt()
			} else if (s.startsWith("Cosmetics.UserFacingFlags.")) {
				val cosmeticSet = cosmeticUserFacingTags.findRowMapped<CosmeticMarkupTagDataRow>(FName(s))
				if (cosmeticSet != null) {
					flags.add(cosmeticSet)
				}
			}
		}
		if (seasonIntroduced != -1) {
			sb.append(L10N.CosmeticItemDescription_Season.format()!!.replace("<SeasonText>", "**").replace("</>", "**").replace("{0}", getFriendlySeasonText(seasonIntroduced)))
		}
		if (flags.isNotEmpty()) {
			sb.append("\n")
			var b = false
			for (flag in flags) {
				if (b) {
					sb.append(", ")
				}
				sb.append('[').append('_' + flag.DisplayName.format()!! + '_').append(']')
				b = true
			}
		}
	}
	return sb.toString()
}

val FortItemStack.shortDescription get() = getShortDescription(true)

fun FortItemStack.getShortDescription(bPickFromDefData: Boolean = true): FText {
	val defData = if (primaryAssetName.isEmpty()) null else defData
	if (bPickFromDefData) {
		defData?.ShortDescription?.let { return it }
	}
	return when (defData) {
		null -> FText(primaryAssetType)

		// AthenaCosmeticItemDefinition
		is AthenaBackpackItemDefinition -> FText("", "0042E3154A36FA07C60F3AB87A77B8E1", "Back Bling")
		is AthenaCharacterItemDefinition -> FText("", "03828EAA442292F10A3EAB87F54DEC87", "Outfit")
		is AthenaDanceItemDefinition -> L10N.Emote
		is AthenaGliderItemDefinition -> FText("", "1057574E47BE3E485D8D24967230D4D0", "Glider")
		is AthenaItemWrapDefinition -> FText("Fort.Cosmetics", "ItemWrapShortDescription", "Wrap")
		is AthenaLoadingScreenItemDefinition -> L10N.LoadingScreen
		is AthenaMusicPackItemDefinition -> FText("", "073E6AAC4A91D413AAB793A6DD82FA06", "Music")
		is AthenaPickaxeItemDefinition -> L10N.WeaponHarvest

		// FortWorldItemDefinition
		is FortAmmoItemDefinition -> L10N.Ammo
		is FortIngredientItemDefinition -> L10N.Ingredient

			// FortWeaponItemDefinition
			is FortWeaponRangedItemDefinition -> L10N.WeaponRanged
			is FortWeaponMeleeItemDefinition -> L10N.WeaponMelee
			is FortWeaponItemDefinition -> L10N.Weapon

		// FortAccountItemDefinition
		is FortCardPackItemDefinition -> L10N.CardPack
		is FortPersistentResourceItemDefinition -> L10N.AccountResource
		is FortSchematicItemDefinition -> L10N.Schematic
		is FortTokenType -> L10N.Token

			// FortCharacterType
			is FortDefenderItemDefinition -> L10N.Defender
			is FortHeroType -> L10N.Hero
			is FortWorkerType -> L10N.Worker

		else -> FText(primaryAssetType)
	}
}

enum class ShowRarityOption {
	SHOW, SHOW_DEFAULT_EMOTE, HIDE
}

fun FortItemStack.render(displayQty: Int = quantity, showType: Boolean = false, showRarity: ShowRarityOption = ShowRarityOption.SHOW): String {
	var showType = showType
	var itemTypeResolver: ItemTypeResolver? = null
	val sb = StringBuilder()

	// Rarity
	if (showRarity != ShowRarityOption.HIDE) {
		if (showRarity == ShowRarityOption.SHOW_DEFAULT_EMOTE) {
			val defaultRarityEmotes = listOf("⬜", "🟩", "🟦", "🟪", "🟧", "🟨")
			sb.append(defaultRarityEmotes[rarity.ordinal])
		} else {
			sb.append(getEmoteByName(rarity.name.toLowerCase() + '2')?.asMention ?: rarity.rarityName.format())
		}
		sb.append(' ')
	}

	// Quantity
	if (displayQty > 1) {
		sb.append(Formatters.num.format(displayQty) + " \u00d7 ")
	}

	// Icons
	if (defData is FortAccountItemDefinition) {
		if (itemTypeResolver == null) {
			itemTypeResolver = ItemTypeResolver.resolveItemType(this)
		}
		textureEmote(itemTypeResolver.middleImg)?.let { sb.append(it.asMention) }
		textureEmote(itemTypeResolver.rightImg)?.let { sb.append(it.asMention) }
		textureEmote(itemTypeResolver.leftImg)?.let { sb.append(it.asMention) }
		sb.append(' ')
	}

	// Level
	val level = level
	if (level > 0) {
		sb.append("Lv%,d ".format(level))
	}

	// Tier name
	(transformedDefData as? FortWeaponItemDefinition)?.DisplayTier?.let {
		sb.append(ItemUtils.getDisplayTierFmtString(it).format() + ' ')
	}

	// Display name
	var dn = displayName
	if (dn.isEmpty() && defData is FortWorkerType) {
		val asWorker = defData as FortWorkerType
		dn = if (asWorker.bIsManager) "Lead Survivor" else "Survivor"
		showType = false
	}
	if (dn.isEmpty() && defData is FortDefenderItemDefinition) {
		if (itemTypeResolver == null) {
			itemTypeResolver = ItemTypeResolver.resolveItemType(this)
		}
		dn = itemTypeResolver.tertiaryCategory?.CategoryName?.format() ?: "Defender"
	}
	if (dn.isEmpty()) {
		dn = primaryAssetName
	}
	sb.append(dn)

	// Type
	if (showType) {
		sb.append(" ($shortDescription)")
	}

	return sb.toString()
}

fun FortItemStack.renderWithIcon(displayQty: Int = quantity, bypassWhitelist: Boolean = false, showType: Boolean = false): String {
	transformedDefData // resolves this item if it is FortConditionalResourceItemDefinition
	return (getItemIconEmoji(this, bypassWhitelist)?.run { "$asMention " } ?: "") + render(displayQty, showType = showType, showRarity = ShowRarityOption.HIDE)
}

fun getSurvivorPersonalityText(personalityTag: String): FText? {
	val homebaseData = managerData
	if (homebaseData != null) {
		for (personality in homebaseData.WorkerPersonalities) {
			if (personality.PersonalityTypeTag.toString() == personalityTag) {
				return personality.PersonalityName
			}
		}
	}
	return null
}

fun getSurvivorSetBonusText(setBonusTag: String): FText? {
	val homebaseData = managerData
	if (homebaseData != null) {
		for (setBonus in homebaseData.WorkerSetBonuses) {
			if (setBonus.SetBonusTypeTag.toString() == setBonusTag) {
				return setBonus.DisplayName
			}
		}
	}
	return null
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
fun CatalogItemPrice.renderText(quantity: Int = 1) = (if (basePrice != -1) Formatters.num.format(quantity * basePrice) else "<unresolved>") + if (regularPrice != basePrice) " (was: ${Formatters.num.format(quantity * regularPrice)})" else ""

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
		if (lastIsSelectable && it.value.Selectable) {
			lines.add("$orPrefix- OR -")
		}
		lastIsSelectable = it.value.Selectable
		lines.add("$prefix$fmt${it.value.asItemStack().apply { setConditionForConditionalItem(conditionalCondition) }.renderWithIcon((it.value.Quantity * fac).toInt())}$fmt")
	}
	return lines
}

fun AthenaRewardItemReference.safeRender(): String {
	val itemDef = ItemDefinition?.load<FortItemDefinition>()
	return if (itemDef == null) {
		"%s".format(ItemDefinition.toString().substringAfterLast('.'))
	} else {
		asItemStack().render(showType = true)
	}
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

fun StringReader.checkConsumed(constraint: Set<Char>) {
	if (canRead() && peek() !in constraint) {
		throw SimpleCommandExceptionType(LiteralMessage("Unrecognized argument")).createWithContext(this)
	}
}

fun StringReader.readString0(terminators: Set<Char>): String {
	if (!canRead()) {
		return ""
	}
	if (StringReader.isQuotedStringStart(peek())) {
		return readQuotedString()
	}
	val start = cursor
	while (canRead() && peek() !in terminators) {
		skip()
	}
	return string.substring(start, cursor)
}

fun confirmationButtons() = ActionRow.of(
	Button.of(ButtonStyle.SUCCESS, "positive", "Confirm"),
	Button.of(ButtonStyle.DANGER, "negative", "Decline")
)

@Throws(CommandSyntaxException::class)
fun Message.awaitConfirmation(author: User, inFinalizeComponentsOnEnd: Boolean = true, inTime: Long = 60000L): CompletableFuture<Boolean> = CompletableFuture.supplyAsync {
	awaitOneInteraction(author, inFinalizeComponentsOnEnd, inTime).componentId == "positive"
}

fun Message.awaitOneReaction(source: CommandSourceStack, inTime: Long = 60000L) =
	awaitReactions({ _, user, _ -> user?.idLong == source.author.idLong }, AwaitReactionsOptions().apply {
		max = 1
		time = inTime
		errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
	}).await().first().reactionEmote.name

fun Message.awaitOneInteraction(author: User, inFinalizeComponentsOnEnd: Boolean = true, inTime: Long = 60000L): ComponentInteraction {
	val interaction = awaitMessageComponentInteractions({ _, user, _ -> user?.idLong == author.idLong }, AwaitMessageComponentInteractionsOptions().apply {
		max = 1
		time = inTime
		errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		finalizeComponentsOnEnd = inFinalizeComponentsOnEnd
	}).await().first()
	interaction.deferEdit().queue()
	return interaction
}

fun Message.finalizeComponents(selectedIds: Collection<String>) {
	editMessageComponents(actionRows.map { row ->
		ActionRow.of(*row.components.map {
			when (it) {
				is Button -> it.withStyle(if (it.id in selectedIds) ButtonStyle.SUCCESS else ButtonStyle.SECONDARY).asDisabled()
				is SelectMenu -> it.asDisabled()
				else -> throw AssertionError()
			}
		}.toTypedArray())
	}).queue()
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

//inline fun String?.escapeMarkdown() = if (this == null) null else MarkdownSanitizer.escape(this) // JDA's MarkdownSanitizer does not sanitize single underscores
fun String?.escapeMarkdown() = if (this == null) null else replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("~", "\\~")

fun Array<FriendV2>.sortedFriends(source: CommandSourceStack) = sortedBy {
	(if (!it.alias.isNullOrEmpty()) it.alias else {
		val displayName = it.getDisplayName(source)
		if (!displayName.isNullOrEmpty()) displayName else it.accountId
	}).toLowerCase()
}

fun FriendV2.getDisplayName(source: CommandSourceStack) = source.userCache[accountId]?.displayName

val CHAPTER_SEASON_STARTS = arrayOf(1, 11, 19)

fun getChapterAndSeason(seasonNum: Int): Pair<String?, String> {
	for (i in CHAPTER_SEASON_STARTS.size - 1 downTo 0) {
		if (seasonNum >= CHAPTER_SEASON_STARTS[i]) {
			val chapterNum = i + 1
			val seasonNumInChapter = (seasonNum - CHAPTER_SEASON_STARTS[i]) + 1
			return Pair(if (chapterNum > 1) Formatters.num.format(chapterNum) else null, if (seasonNumInChapter == 10) "X" else Formatters.num.format(seasonNumInChapter))
		}
	}
	return Pair(null, Formatters.num.format(seasonNum))
}

fun getFriendlySeasonText(seasonNum: Int): String {
	val (chapter, season) = getChapterAndSeason(seasonNum)
	return if (chapter != null) "Chapter $chapter, Season $season" else "Season $season"
}

val psnEmote by lazy { textureEmote("/Game/UI/Friends_UI/Social/PS4_w-backing_PlatformIcon_64x.PS4_w-backing_PlatformIcon_64x") }
val xblEmote by lazy { textureEmote("/Game/UI/Friends_UI/Social/xBox_PlatformIcon_64x.xBox_PlatformIcon_64x") }
val switchEmote by lazy { textureEmote("/Game/UI/Friends_UI/Social/Switch_PlatformIcon_64x.Switch_PlatformIcon_64x") }

fun externalAuthEmote(externalAuthType: String) = when (externalAuthType) {
	"psn" -> psnEmote
	"xbl" -> xblEmote
	"nintendo" -> switchEmote
	else -> null
}

val PUBLIC_EXTERNAL_AUTH_TYPES = arrayOf("psn", "xbl", "nintendo")

fun GameProfile.renderPublicExternalAuths() = if (externalAuths == null) emptyList() else PUBLIC_EXTERNAL_AUTH_TYPES.mapNotNull { externalAuths[it] }.map {
	val type = it.type
	val externalDisplayName = it.externalDisplayName
	(externalAuthEmote(type)?.asMention ?: type) + ' ' + (if (externalDisplayName.isNullOrEmpty()) "<linked>" else externalDisplayName.escapeMarkdown())
}

val Token.jwtPayload: JsonObject? get() {
	if (!access_token.startsWith("eg1~")) {
		return null
	}
	val (_, payload) = access_token.split('.')
	val payloadJson = String(Base64.getUrlDecoder().decode(payload))
	return JsonParser.parseString(payloadJson).asJsonObject
}

// region Copy of Throwable.printStackTrace but with frame limit
fun Throwable.getStackTraceAsString(limit: Int = 4): String {
	val writer = StringWriter()
	val s = PrintWriter(writer)

	// Guard against malicious overrides of Throwable.equals by
	// using a Set with identity equality semantics.
	val dejaVu = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
	dejaVu.add(this)

	// Print our stack trace
	s.println(this)
	val trace = stackTrace
	for (i in 0 until min(limit, trace.size))
		s.println("\tat " + trace[i])
	if (trace.size > limit)
		s.println("\t... " + (trace.size - limit) + " more")

	// Print suppressed exceptions, if any
	for (se in suppressed)
		se.printEnclosedStackTrace(s, trace, "Suppressed: ", "\t", dejaVu, limit)

	// Print cause, if any
	cause?.printEnclosedStackTrace(s, trace, "Caused by: ", "", dejaVu, limit)

	return writer.toString()
}

private fun Throwable.printEnclosedStackTrace(s: PrintWriter,
											  enclosingTrace: Array<StackTraceElement>,
											  caption: String,
											  prefix: String,
											  dejaVu: MutableSet<Throwable>,
											  limit: Int) {
	if (dejaVu.contains(this)) {
		s.println("$prefix$caption[CIRCULAR REFERENCE: $this]")
	} else {
		dejaVu.add(this)
		// Compute number of frames in common between this and enclosing trace
		val trace = stackTrace
		var m = trace.size - 1
		var n = enclosingTrace.size - 1
		while (m >= 0 && n >= 0 && trace[m] == enclosingTrace[n]) {
			m--; n--
		}
		m = min(limit - 1, m)
		val framesInCommon = trace.size - 1 - m

		// Print our stack trace
		s.println(prefix + caption + this)
		for (i in 0..m)
			s.println(prefix + "\tat " + trace[i])
		if (framesInCommon != 0)
			s.println("$prefix\t... $framesInCommon more")

		// Print suppressed exceptions, if any
		for (se in suppressed)
			se.printEnclosedStackTrace(s, trace, "Suppressed: ", prefix + "\t", dejaVu, limit)

		// Print cause, if any
		cause?.printEnclosedStackTrace(s, trace, "Caused by: ", prefix, dejaVu, limit)
	}
}
// endregion

inline fun CatalogOffer.holder() = CatalogEntryHolder(this)

inline fun String?.orDash() = if (isNullOrEmpty()) "\u2014" else this

inline fun Date.renderWithRelative() = "${format()} (${relativeFromNow()})"

inline fun Date.relativeFromNow(withSeconds: Boolean = false) = time.relativeFromNow(withSeconds)

/*fun Long.relativeFromNow(withSeconds: Boolean = false): String {
	val delta = System.currentTimeMillis() - this
	val elapsedStr = StringUtil.formatElapsedTime(abs(delta), withSeconds).toString()
	return when {
		delta < 0L -> "in $elapsedStr"
		delta < 60L -> "just now"
		else *//*delta > 0L*//* -> "$elapsedStr ago"
	}
}*/

inline fun Long.relativeFromNow(withSeconds: Boolean = false) = TimeFormat.RELATIVE.format(this)

fun String.shortenUrl(source: CommandSourceStack): String {
	val cuttlyApiKey = "2f305deea48f34be34018ab54b7b7dd2b72e4"
	val shortenerUrl = "https://cutt.ly/api/api.php".toHttpUrl().newBuilder().addQueryParameter("key", cuttlyApiKey).addQueryParameter("short", this).build()
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

fun searchItemDefinition(displayName: String, primaryAssetType: String, className: String? = null): Pair<String, FortItemDefinition>? {
	val lowerPrimaryAssetType = primaryAssetType.toLowerCase()
	for ((templateId, objectPath) in AssetManager.INSTANCE.assetRegistry.templateIdToObjectPathMap.entries) {
		if (!templateId.startsWith(lowerPrimaryAssetType)) {
			continue
		}
		val itemDef = loadObject<FortItemDefinition>(objectPath) ?: continue
		if (className != null && itemDef.exportType != className) {
			continue
		}
		if (itemDef.DisplayName.format()?.trim()?.equals(displayName, true) == true) {
			return templateId to itemDef
		}
	}
	return null
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

fun BuildResponse.ManifestDownloadInfo.createRequest(): Request {
	val url = StringBuilder(uri)
	if (queryParams.isNotEmpty()) {
		url.append("?")
		url.append(queryParams.joinToString("&") { it.name + "=" + it.value })
	}
	val builder = Request.Builder().url(url.toString())
	for (header in headers ?: emptyList()) {
		builder.addHeader(header.name, header.value)
	}
	return builder.build()
}

fun Number.awtColor(hasAlpha: Boolean = toInt() ushr 24 != 0) = Color(toInt(), hasAlpha)

inline fun createAndDrawCanvas(w: Int, h: Int, withAlpha: Boolean = true, draw: (ctx: Graphics2D) -> Unit): BufferedImage {
	val canvas = BufferedImage(w, h, if (withAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
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
	val primaryColor = 0x33EEFF

	private fun fromPaks(path: String) = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(AssetManager.INSTANCE.provider.saveGameFile(path)))
}