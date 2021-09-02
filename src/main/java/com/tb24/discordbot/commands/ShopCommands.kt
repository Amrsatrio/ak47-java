package com.tb24.discordbot.commands

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.L10N
import com.tb24.discordbot.images.FMergedMaterialParams
import com.tb24.discordbot.images.generateShopImage
import com.tb24.discordbot.managers.CatalogManager
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortCmsData
import com.tb24.fn.model.assetdata.ESubGame
import com.tb24.fn.model.gamesubcatalog.CatalogDownload
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.CatalogOffer.CatalogItemPrice
import com.tb24.fn.model.gamesubcatalog.ECatalogOfferType
import com.tb24.fn.model.gamesubcatalog.EStoreCurrencyType
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.PopulatePrerolledOffers
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.fn.util.getPathName
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.AthenaItemShopOfferDisplayData
import me.fungames.jfortniteparse.fort.exports.FortMtxOfferData
import me.fungames.jfortniteparse.fort.exports.FortRarityData
import me.fungames.jfortniteparse.fort.objects.FortColorPalette
import me.fungames.jfortniteparse.ue4.assets.exports.mats.UMaterialInstance
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.io.InputStreamReader
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CompletableFuture

class ShopCommand : BrigadierCommand("shop", "Sends an image of today's item shop.", arrayOf("s")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { executeShopImage(it.source) }
}

class ShopTextCommand : BrigadierCommand("shoptext", "Sends the current item shop items as a text.", arrayOf("st")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { executeShopText(it.source, ESubGame.Athena) }
}

class CampaignShopCommand : BrigadierCommand("stwshop", "Sends the current Save the World item shop items as a text.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { executeShopText(it.source, ESubGame.Campaign) }
}

class ShopDumpCommand : BrigadierCommand("shopdump", "Sends the current item shop as a JSON.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			if (source.api.userToken == null) {
				source.session = source.client.internalSession
			}
			val lang = "en"
			val data = source.api.okHttpClient.newCall(source.api.fortniteService.storefrontCatalog(lang).request()).exec().body()!!.charStream().use(JsonParser::parseReader)
			val df = SimpleDateFormat("dd-MM-yyyy").apply { timeZone = TimeZone.getTimeZone("UTC") }
			source.channel.sendFile(GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(data).toByteArray(), "shop-%s-%s.json".format(df.format(Date()), lang)).complete()
			Command.SINGLE_SUCCESS
		}
}

fun executeShopImage(source: CommandSourceStack): Int {
	val attachedFile = source.message.attachments.firstOrNull()
	if (attachedFile != null) {
		source.loading("Processing your request")
		val catalogManager = CatalogManager()
		catalogManager.catalogData = InputStreamReader(attachedFile.retrieveInputStream().await()).use { EpicApi.GSON.fromJson(it, CatalogDownload::class.java) }
		catalogManager.sectionsData = OkHttpClient().newCall(Request.Builder().url("https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game/shop-sections").build()).exec().to<FortCmsData.ShopSectionsData>()
		catalogManager.validate()
		source.channel.sendFile(generateShopImage(catalogManager, 2).toPngArray(), attachedFile.fileName.substringBeforeLast('.') + ".png").complete()
		source.loadingMsg!!.delete().queue()
		return Command.SINGLE_SUCCESS
	}
	if (isUserAnIdiot(source)) {
		return Command.SINGLE_SUCCESS
	}
	source.ensureSession()
	source.loading("Getting the shop")
	source.client.catalogManager.ensureCatalogData(source.client.internalSession.api)
	source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
	val image = generateShopImage(source.client.catalogManager, 2).toPngArray()
	val tz = TimeZone.getTimeZone("UTC")
	val now = Date()
	val fileName = "shop-${SimpleDateFormat("dd-MM-yyyy").apply { timeZone = tz }.format(now)}.png"
	val message = source.channel.sendMessage("**Battle Royale Item Shop (%s)**".format(DateFormat.getDateInstance().apply { timeZone = tz }.format(now))).addFile(image, fileName).complete()
	source.loadingMsg!!.delete().queue()
	if (source.channel.idLong == BotConfig.get().itemShopChannelId) {
		message.crosspost().queue()
	}
	return Command.SINGLE_SUCCESS
}

fun executeShopText(source: CommandSourceStack, subGame: ESubGame): Int {
	if (subGame == ESubGame.Athena && isUserAnIdiot(source)) {
		return Command.SINGLE_SUCCESS
	}
	source.ensureSession()
	source.loading("Getting the shop")
	val catalogManager = source.client.catalogManager
	val profileManager = source.api.profileManager
	catalogManager.ensureCatalogData(source.client.internalSession.api)
	CompletableFuture.allOf(
		profileManager.dispatchClientCommandRequest(QueryProfile()),
		if (subGame == ESubGame.Campaign) {
			profileManager.dispatchClientCommandRequest(PopulatePrerolledOffers(), "campaign")
		} else {
			profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
		}
	).await()
	val showAccInfo = source.channel.idLong != BotConfig.get().itemShopChannelId && source.session.id != "__internal__"
	val sections = if (subGame == ESubGame.Campaign) catalogManager.campaignSections.values else catalogManager.athenaSections.values
	val contents = arrayOfNulls<List<String>>(sections.size)
	val prices = mutableMapOf<String, CatalogItemPrice>()
	for ((i, section) in sections.withIndex()) {
		val lines = mutableListOf<String>()
		for (catalogEntry in section.items) {
			/*if (catalogEntry.shouldBeSkipped(commonCore)) {
				continue
			}*/
			if (catalogEntry.offerType == ECatalogOfferType.StaticPrice && (catalogEntry.prices.isEmpty() || catalogEntry.prices.first().currencyType == EStoreCurrencyType.RealMoney)) continue
			val sd = catalogEntry.holder().apply { resolve(profileManager) }
			lines.add("%,d. %s%s".format(catalogEntry.__ak47_index + 1, sd.friendlyName, if (showAccInfo && (sd.owned || sd.purchaseLimit >= 0 && sd.purchasesCount >= sd.purchaseLimit)) " ✅" else if (showAccInfo && !sd.eligible) " ❌" else ""))
			catalogEntry.prices.forEach { prices.putIfAbsent(it.currencyType.name + ' ' + it.currencySubType, it) }
		}
		contents[i] = lines
	}
	val embed = EmbedBuilder()
		.setColor(0x0099FF)
		.setTitle(if (subGame == ESubGame.Campaign) "⚡ " + "Save the World Item Shop" else "☂ " + "Battle Royale Item Shop")
	if (showAccInfo) {
		embed.setDescription("Use `${source.prefix}buy` or `${source.prefix}gift` to perform operations with these items.\n✅ = Owned/sold out")
			.addField(if (prices.size == 1) "Balance" else "Balances", prices.values.joinToString(" \u00b7 ") { it.getAccountBalanceText(profileManager) }, false)
	}
	for ((i, section) in sections.withIndex()) {
		if (contents[i].isNullOrEmpty()) {
			continue
		}
		embed.addFieldSeparate(section.sectionData.sectionDisplayName ?: "", contents[i], 0)
	}
	// Replace V-Bucks emote with V character to try to reduce the length
	if (embed.length() > MessageEmbed.EMBED_MAX_LENGTH_BOT) {
		val iterator = embed.fields.listIterator()
		while (iterator.hasNext()) {
			val field = iterator.next()
			iterator.set(MessageEmbed.Field(field.name, field.value?.replace(Utils.MTX_EMOJI, "V"), field.isInline))
		}
	}
	val message = source.complete(null, embed.build())
	if (!showAccInfo) {
		message.crosspost().queue()
	}
	return Command.SINGLE_SUCCESS
}

private fun isUserAnIdiot(source: CommandSourceStack): Boolean {
	if (source.channel.idLong == 709667951300706385L || source.channel.idLong == 708845713592811603L) {
		source.message.reply("Hey ${source.author.asMention}, in this server there is an <#702307657989619744> channel.\nIf you believe that it is outdated, you can DM one of us to update it.").complete()
		return true
	}
	return false
}

val rarityData by lazy { loadObject<FortRarityData>("/Game/Balance/RarityData.RarityData")!! }

class OfferDisplayData {
	var banner: String? = null
	var image: BufferedImage? = null
	var imagePath: String? = null
	var presentationParams: FMergedMaterialParams? = null
	var title: String? = null
	var subtitle: String? = null
	var palette: FortColorPalette? = null

	constructor(offer: CatalogOffer, loadImage: Boolean = false, loadDAV2: Boolean = true) {
		val firstGrant = offer.itemGrants.firstOrNull()
		title = offer.title
		subtitle = offer.shortDescription
		if (!offer.displayAssetPath.isNullOrEmpty()) {
			loadObject<FortMtxOfferData>(offer.displayAssetPath)?.also {
				if (title == null) it.DisplayName?.run { title = format() }
			}
		}
		if (offer.offerType == ECatalogOfferType.DynamicBundle) {
			if (subtitle == null) subtitle = L10N.TOTAL_BUNDLE_ITEMS.format()?.replace("{total bundle items}", Formatters.num.format(offer.dynamicBundleInfo.bundleItems.size))
		}
		firstGrant?.also { item ->
			val defData = item.defData
			val softPath = item.getPreviewImagePath(true)
			if (loadImage) image = softPath?.load<UTexture2D>()?.toBufferedImage()
			imagePath = softPath.toString()
			if (title == null) title = item.displayName
			if (subtitle == null) subtitle = defData?.ShortDescription?.format()
			palette = rarityData.forRarity(item.rarity)
			defData?.Series?.value?.also {
				palette = it.Colors
			}
		}
		val newDisplayAssetPath = offer.getMeta("NewDisplayAssetPath")
		if (loadDAV2 && newDisplayAssetPath != null) {
			val newDisplayAsset = loadObject<AthenaItemShopOfferDisplayData>(newDisplayAssetPath)
			if (newDisplayAsset != null) {
				val firstPresentation = newDisplayAsset.Presentations.first().value as? UMaterialInstance
				if (firstPresentation != null) {
					presentationParams = FMergedMaterialParams(firstPresentation)
					val tex = presentationParams!!.texture["OfferImage"]
					if (loadImage) image = (tex?.value as? UTexture2D)?.toBufferedImage()
					imagePath = tex?.getPathName()
				}
			}
		}
		// TODO banner
	}
}