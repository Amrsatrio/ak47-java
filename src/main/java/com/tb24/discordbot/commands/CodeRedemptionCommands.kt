package com.tb24.discordbot.commands

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.StringArgument2.Companion.string2
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.RedeemCodePayload
import com.tb24.fn.model.catalog.StoreOffer
import com.tb24.fn.model.coderedemption.EvaluateCodeResponse
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.api.EmbedBuilder
import java.text.NumberFormat
import java.util.*
import kotlin.math.pow

class CheckCodeCommand : BrigadierCommand("checkcode", "Evaluates an Epic Games code.", arrayOf("evaluatecode")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("code", string2())
			.executes { executeCheckCode(it.source, getString(it, "code")) }
			.then(literal("raw")
				.executes { executeCheckCode(it.source, getString(it, "code"), true) }
			)
		)

	private fun executeCheckCode(source: CommandSourceStack, code: String, raw: Boolean = false): Int {
		source.conditionalUseInternalSession()
		source.ensureSession()
		source.loading("Checking code")
		val launcherApi = source.session.getApiForOtherClient(EAuthClient.LAUNCHER_APP_CLIENT_2)
		val code = code.replace("-", "")
		if (raw) {
			val evaluateCodeResponse = launcherApi.okHttpClient.newCall(launcherApi.codeRedemptionService.evaluateCode(launcherApi.currentLoggedIn.id, code).request()).exec().to<JsonObject>()
			val data = EpicApi.GSON.fromJson(evaluateCodeResponse, EvaluateCodeResponse::class.java)
			val offerId = data.consumptionMetadata?.offerId ?: throw SimpleCommandExceptionType(LiteralMessage("Invalid code (offer ID unknown)")).create()
			val offerInfoResponse = launcherApi.okHttpClient.newCall(launcherApi.catalogService.queryOffersBulk(listOf(offerId), null, null, null).request()).exec().to<JsonObject>()
			val offerInfo = offerInfoResponse.get(offerId)
			val prettyPrintGson = GsonBuilder().setPrettyPrinting().create()
			val files = ArrayList<AttachmentUpload>(2)
			files.add(AttachmentUpload(prettyPrintGson.toJson(evaluateCodeResponse).toByteArray(), "Code-${data.code}.json"))
			if (offerInfo != null) {
				files.add(AttachmentUpload(prettyPrintGson.toJson(offerInfo).toByteArray(), "Offer-${offerId}.json"))
			}
			source.complete(*files.toTypedArray())
			return Command.SINGLE_SUCCESS
		}
		val data = launcherApi.codeRedemptionService.evaluateCode(launcherApi.currentLoggedIn.id, code).exec().body()!!
		val offerId = data.consumptionMetadata?.offerId ?: throw SimpleCommandExceptionType(LiteralMessage("Invalid code (offer ID unknown)")).create()
		val offerInfo = runCatching { launcherApi.catalogService.queryOffersBulk(listOf(offerId), null, null, null).exec().body()!!.values.first() }.getOrNull()
		val embed = EmbedBuilder().setColor(COLOR_INFO)
			.setTitle("✅ Valid code")
			.populateOffer(offerInfo)
			.addField("Code Creation", data.dateCreated.format(), false)
			.addField("Period", "%s \u2013 %s".format(data.startDate.format(), data.endDate.format()), false)
			.addField("Uses", "`%s`\n%,d / %,d".format(Utils.progress(data.useCount, data.maxNumberOfUses, 32), data.useCount, data.maxNumberOfUses), false)
			.addField("Batch No", "%,d".format(data.batchNumber), false)
			.setFooter(data.code)
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}

class RedeemCodeCommand : BrigadierCommand("redeemcode", "Redeems an Epic Games code.", arrayOf("redeem")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("code", greedyString())
			.executes {
				val source = it.source
				source.ensureSession()
				source.loading("Redeeming code")
				val launcherApi = source.session.getApiForOtherClient(EAuthClient.LAUNCHER_APP_CLIENT_2)
				val code = getString(it, "code").replace("-", "")
				val lockCodeResponse = launcherApi.codeRedemptionService.lockCode(code).exec().body()!!
				val offerId = lockCodeResponse.consumptionMetadata?.offerId ?: throw SimpleCommandExceptionType(LiteralMessage("Invalid code (offer ID unknown)")).create()
				val redeemCodeResponse = launcherApi.fulfillmentService.redeemCode(launcherApi.currentLoggedIn.id, code, lockCodeResponse.codeUseId, RedeemCodePayload().apply { fulfillmentSource = "DieselWebClient" }).exec().body()!!
				val offerInfo = runCatching { launcherApi.catalogService.queryOffersBulk(listOf(offerId), null, null, null).exec().body()!!.values.first() }.getOrNull()
				source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
					.setTitle("✅ Code redeemed")
					.populateOffer(offerInfo)
					.setFooter(code)
					.build())
				Command.SINGLE_SUCCESS
			}
		)
}

fun EmbedBuilder.populateOffer(offer: StoreOffer?, populatePrice: Boolean = true): EmbedBuilder {
	if (offer == null) {
		setDescription("Offer information unknown")
		return this
	}
	val name = offer.title
	val description = offer.description
	val seller = offer.seller?.name ?: "Unknown"
	val sb = StringBuilder()
	sb.append("**$name**")
	if (description != null && description != name) {
		sb.append("\n$description")
	}
	sb.append("\nby ").append(seller)
	setDescription(sb.toString())
	val dates = listOf(
		"Creation" to offer.creationDate,
		"Last Modified" to offer.lastModifiedDate,
		"Viewable" to offer.viewableDate,
		"Effective" to offer.effectiveDate,
		"Expiry" to offer.expiryDate,
		"PC Release" to offer.pcReleaseDate,
	).filter { it.second != null }.groupBy { it.second }.entries.joinToString("\n") { it.value.joinToString() { it.first } + ": " + it.key.format() }
	addField("Offer Dates", dates, false)
	if (populatePrice && (offer.price ?: 0) != 0) {
		val priceFormatter = NumberFormat.getCurrencyInstance()
		priceFormatter.currency = Currency.getInstance(offer.currencyCode)
		priceFormatter.minimumFractionDigits = offer.currencyDecimals
		addField("Price", priceFormatter.format(offer.price / 10.0.pow(offer.currencyDecimals.toDouble())), false)
	}
	if (!offer.keyImages.isNullOrEmpty()) {
		setImage((offer.getImage("OfferImageWide") ?: offer.getImage("Thumbnail") ?: offer.keyImages.firstOrNull())?.url)
	}
	return this
}

fun StoreOffer.getImage(type: String) = keyImages?.filter { it.type == type }?.maxByOrNull { it.width * it.height }