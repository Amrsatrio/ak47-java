package com.tb24.discordbot.commands

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.L10N
import com.tb24.discordbot.Session
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.images.FMergedMaterialParams
import com.tb24.discordbot.images.generateShopImage
import com.tb24.discordbot.managers.CatalogManager
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortCmsData
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.assetdata.ESubGame
import com.tb24.fn.model.gamesubcatalog.CatalogDownload
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.CatalogOffer.CatalogItemPrice
import com.tb24.fn.model.gamesubcatalog.ECatalogOfferType
import com.tb24.fn.model.gamesubcatalog.EStoreCurrencyType
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.PopulatePrerolledOffers
import com.tb24.fn.model.mcpprofile.commands.commoncore.PurchaseCatalogEntry
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
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.NewsChannel
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
		.then(literal("buyall")
			.executes{ execBuyAllCampaign(it.source) }
			.then(literal("bulk")
				.executes{ execBuyAllCampaignBulk(it.source, null) }
			)
			.then(argument("bulk users", UserArgument.users(-1))
				.executes { execBuyAllCampaignBulk(it.source, UserArgument.getUsers(it, "bulk users", loadingText = null)) }
			)

		)
}

class ShopDumpCommand : BrigadierCommand("shopdump", "Sends the current item shop as a JSON.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.conditionalUseInternalSession()
			val lang = "en"
			val data = source.api.okHttpClient.newCall(source.api.fortniteService.storefrontCatalog(lang).request()).exec().body()!!.charStream().use(JsonParser::parseReader)
			val df = SimpleDateFormat("dd-MM-yyyy").apply { timeZone = TimeZone.getTimeZone("UTC") }
			source.channel.sendFile(GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(data).toByteArray(), "shop-%s-%s.json".format(df.format(Date()), lang)).complete()
			Command.SINGLE_SUCCESS
		}
}

fun executeShopImage(source: CommandSourceStack): Int {
	val attachedFile = source.message?.attachments?.firstOrNull()
	if (attachedFile != null) {
		source.loading("Processing your request")
		val catalogManager = CatalogManager()
		catalogManager.catalogData = InputStreamReader(attachedFile.retrieveInputStream().await()).use { EpicApi.GSON.fromJson(it, CatalogDownload::class.java) }
		catalogManager.sectionsData = OkHttpClient().newCall(Request.Builder().url("https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game/shop-sections").build()).exec().to<FortCmsData.ShopSectionsData>()
		catalogManager.validate()
		source.complete(AttachmentUpload(generateShopImage(catalogManager, 2).toPngArray(), attachedFile.fileName.substringBeforeLast('.') + ".png"))
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
	val message = source.complete(MessageBuilder("**Battle Royale Item Shop (%s)**".format(DateFormat.getDateInstance().apply { timeZone = tz }.format(now))).build(), AttachmentUpload(image, fileName))
	if (source.channel.idLong == BotConfig.get().itemShopChannelId) {
		/*message.addReaction("👍").queue()
		message.addReaction("👎").queue()*/
		if (source.channel is NewsChannel) {
			message.crosspost().queue()
		}
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
	val isCampaign = subGame == ESubGame.Campaign
	CompletableFuture.allOf(
		profileManager.dispatchClientCommandRequest(QueryProfile()),
		if (isCampaign) {
			profileManager.dispatchClientCommandRequest(PopulatePrerolledOffers(), "campaign")
		} else {
			profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
		}
	).await()
	val showAccInfo = source.channel.idLong != BotConfig.get().itemShopChannelId
	val sections = if (isCampaign) catalogManager.campaignSections.values else catalogManager.athenaSections.values
	val contents = arrayOfNulls<List<String>>(sections.size)
	val prices = mutableMapOf<String, CatalogItemPrice>()
	var numOwned = 0
	var numShownItems = 0
	for ((i, section) in sections.withIndex()) {
		val lines = mutableListOf<String>()
		for (catalogEntry in section.items) {
			/*if (catalogEntry.shouldBeSkipped(commonCore)) {
				continue
			}*/
			if (catalogEntry.offerType == ECatalogOfferType.StaticPrice && (catalogEntry.prices.isEmpty() || catalogEntry.prices.first().currencyType == EStoreCurrencyType.RealMoney)) continue
			val sd = catalogEntry.holder().apply { resolve(profileManager) }
			val ownedOrSoldOut = showAccInfo && (sd.owned || sd.purchaseLimit >= 0 && sd.purchasesCount >= sd.purchaseLimit)
			if (ownedOrSoldOut && subGame == ESubGame.Athena) {
				numOwned++
			}
			lines.add("%,d. %s%s".format(catalogEntry.__ak47_index + 1, sd.friendlyName, if (ownedOrSoldOut) " ✅" else if (showAccInfo && !sd.eligible) " ❌" else if (sd.purchaseLimit >= 0) " **${sd.purchasesCount}/${sd.purchaseLimit}**" else ""))
			catalogEntry.prices.forEach { prices.putIfAbsent(it.currencyType.name + ' ' + it.currencySubType, it) }
			numShownItems++
		}
		contents[i] = lines
	}
	val embed = EmbedBuilder()
		.setColor(0x0099FF)
		.setTitle(if (isCampaign) "⚡ " + "Save the World Item Shop" else "☂ " + "Battle Royale Item Shop")
	if (showAccInfo) {
		embed.setDescription(if (isCampaign) "Use `${source.prefix}buy` to buy an item listed below." else "Use `${source.prefix}buy` or `${source.prefix}gift` to perform operations with these items.")
			.addField(if (prices.size == 1) "Balance" else "Balances", prices.values.joinToString(" \u00b7 ") { it.getAccountBalanceText(profileManager) }, false)
		if (numOwned > 0) {
			embed.appendDescription("\n**Owned:** %,d/%,d%s".format(numOwned, numShownItems, if (numOwned >= numShownItems) " ✅" else ""))
		}
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
	if (!showAccInfo && source.channel is NewsChannel) {
		message.crosspost().queue()
	}
	return Command.SINGLE_SUCCESS
}

private class BuyAllResult(val totalItems: Int, val purchasedItems: List<String>, val notEnoughBalanceItems: List<String>, val neededGold: Int, val finalBalance: Int, val totalSpent: Int, val ownedAll: Boolean)

private fun buyAll(session: Session): BuyAllResult {
	val profileManager = session.api.profileManager
	CompletableFuture.allOf(
		profileManager.dispatchClientCommandRequest(QueryProfile()),
		profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign")
	).await()
	val campaign = profileManager.getProfileData("campaign")
	val initialBalance = campaign.items.values.firstOrNull { it.templateId == "AccountResource:eventcurrency_scaling" }?.quantity ?: 0
	if (initialBalance == 0) {
		throw SimpleCommandExceptionType(LiteralMessage("No gold.")).create()
	}
	var balance = initialBalance
	var totalItems = 0
	val purchasedItems = mutableListOf<String>()
	val notEnoughBalanceItems = mutableListOf<String>()
	var neededGold = 0
	var ownedAll = true
	for (section in DiscordBot.instance.catalogManager.campaignSections.values) {
		for (catalogEntry in section.items) {
			// Skip items not priced with gold
			if (catalogEntry.prices.firstOrNull()?.currencySubType != "AccountResource:eventcurrency_scaling") {
				continue
			}
			val sd = catalogEntry.holder().apply { resolve(profileManager) }
			// Skip items with unlimited purchases
			if (sd.purchaseLimit == -1) {
				continue
			}
			totalItems++
			// Skip sold out items
			val ownedOrSoldOut = sd.owned || sd.purchaseLimit >= 0 && sd.purchasesCount >= sd.purchaseLimit
			if (ownedOrSoldOut) {
				continue
			}
			ownedAll = false
			val quantity = sd.purchaseLimit - sd.purchasesCount
			val price = quantity * sd.price.basePrice
			if (balance < price) {
				neededGold += price
				notEnoughBalanceItems.add("%dx %s".format(quantity, sd.friendlyName))
				continue
			}
			session.api.profileManager.dispatchClientCommandRequest(PurchaseCatalogEntry().apply {
				offerId = catalogEntry.offerId
				purchaseQuantity = quantity
				currency = sd.price.currencyType
				currencySubType = "AccountResource:eventcurrency_scaling" // Hardcode to make sure we're buying with gold
				expectedTotalPrice = price
				gameContext = "Frontend.ItemShopScreen"
			}).await()
			balance -= price
			purchasedItems.add("%dx %s".format(quantity, sd.friendlyName))
		}
	}
	val totalSpent = initialBalance - balance
	return BuyAllResult(totalItems, purchasedItems, notEnoughBalanceItems, neededGold, balance, totalSpent, ownedAll)
}

private fun execBuyAllCampaign(source: CommandSourceStack): Int {
	source.ensureSession()
	source.loading("Getting offers")
	val catalogManager = source.client.catalogManager
	catalogManager.ensureCatalogData(source.client.internalSession.api)
	val result = buyAll(source.session)
	if (result.purchasedItems.isEmpty()) {
		source.complete(null, source.createEmbed().setColor(BrigadierCommand.COLOR_ERROR).setDescription(if (result.ownedAll) "❌ You already own everything." else "❌ Not enough gold to purchase the remaining offers.").build())
	} else {
		source.complete(null, source.createEmbed().setColor(BrigadierCommand.COLOR_SUCCESS).setTitle("✅ Purchased:").setDescription(result.purchasedItems.joinToString("\n")).setFooter("Total spent: %,d".format(result.totalSpent)).build())
	}
	return Command.SINGLE_SUCCESS
}

private fun execBuyAllCampaignBulk(source: CommandSourceStack, users: Map<String, GameProfile>?): Int {
	val devices = source.client.savedLoginsManager.getAll(source.author.id)
	if (devices.isEmpty()) {
		throw SimpleCommandExceptionType(LiteralMessage("You don't have saved logins. Please perform `.savelogin` before continuing.")).create()
	}
	source.loading("Getting & purchasing offers")
	val catalogManager = source.client.catalogManager
	catalogManager.ensureCatalogData(source.client.internalSession.api)
	val embed = EmbedBuilder().setColor(BrigadierCommand.COLOR_INFO)
	forEachSavedAccounts(source, if (users != null) devices.filter { it.accountId in users } else devices) {
		if (embed.fields.size == 25) {
			source.complete(null, embed.build())
			embed.clearFields()
			source.loading("Purchasing offers")
		}
		val result = try {
			buyAll(it)
		} catch (e: Exception) {
			embed.addField(it.api.currentLoggedIn.displayName, "❌ ${e.message}", false)
			return@forEachSavedAccounts null
		}
		embed.addField(it.api.currentLoggedIn.displayName, when {
			result.ownedAll -> "✅ You already own everything."
			result.totalSpent != 0 -> when {
				result.notEnoughBalanceItems.isNotEmpty() -> "⚠ Purchased %,d/%,d items. %,d gold needed to purchase the remaining %,d offer%s.".format(
					result.purchasedItems.size, result.totalItems,
					result.neededGold, result.notEnoughBalanceItems.size, if (result.notEnoughBalanceItems.size == 1) "" else "s"
				)
				else -> "✅ Purchased %s %,d items. %,d gold remaining.".format(
					if (result.purchasedItems.size == result.totalItems) "all" else "remaining", result.purchasedItems.size,
					result.finalBalance
				)
			}
			else -> "❌ %,d gold needed to purchase the remaining %,d offer%s.".format(
				result.neededGold, result.notEnoughBalanceItems.size, if (result.notEnoughBalanceItems.size == 1) "" else "s"
			)
		}, false)
	}
	source.complete(null, embed.build())
	return Command.SINGLE_SUCCESS
}

private fun isUserAnIdiot(source: CommandSourceStack): Boolean {
	val itemShopChannelId = BotConfig.get().itemShopChannelId
	if (itemShopChannelId != 0L && (source.channel.idLong == 709667951300706385L || source.channel.idLong == 708845713592811603L)) {
		source.complete("Hey ${source.author.asMention}, in this server there is an <#$itemShopChannelId> channel.\nIf you believe that it is outdated, you can DM one of us to update it.")
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
			val softPath = item.getPreviewImagePath(true)
			if (loadImage) image = softPath?.load<UTexture2D>()?.toBufferedImage()
			imagePath = softPath.toString()
			if (title == null) title = item.displayName
			if (subtitle == null) subtitle = item.shortDescription.format()
			palette = item.palette
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