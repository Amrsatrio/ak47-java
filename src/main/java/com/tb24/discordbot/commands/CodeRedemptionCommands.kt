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
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.format
import com.tb24.discordbot.util.to
import com.tb24.fn.EpicApi
import com.tb24.fn.model.RedeemCodePayload
import com.tb24.fn.model.catalog.StoreOffer
import com.tb24.fn.model.coderedemption.EvaluateCodeResponse
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.api.EmbedBuilder

class CheckCodeCommand : BrigadierCommand("checkcode", "Evaluates an Epic Games code.", arrayOf("evaluatecode")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("code", string2())
			.executes { executeCheckCode(it.source, getString(it, "code")) }
			.then(literal("raw")
				.executes { executeCheckCode(it.source, getString(it, "code"), true) }
			)
		)

	private fun executeCheckCode(source: CommandSourceStack, code: String, raw: Boolean = false): Int {
		source.session = source.client.internalSession
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
			val action = source.channel.sendFile(prettyPrintGson.toJson(evaluateCodeResponse).toByteArray(), "Code-${data.code}.json")
			if (offerInfo != null) {
				action.addFile(prettyPrintGson.toJson(offerInfo).toByteArray(), "Offer-${offerId}.json")
			}
			action.complete()
			source.loadingMsg!!.delete().queue()
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

fun EmbedBuilder.populateOffer(offer: StoreOffer?): EmbedBuilder {
	if (offer == null) {
		setDescription("Offer information unknown")
		return this
	}
	val name = offer.title
	val description = offer.description
	val image = offer.keyImages?.getOrNull(0)?.url
	val thumbnail = offer.keyImages?.getOrNull(2)?.url
	val status = offer.status
	val created = offer.creationDate.format()
	val seller = offer.seller?.name ?: "Unknown"
	val sb = StringBuilder()
	sb.append("**$name**")
	if (description != null && description != name) {
		sb.append("\n$description")
	}
	sb.append("\nby ").append(seller)
	setDescription(sb.toString())
	addField("Offer Creation", created, false)
	setImage(image)
	setThumbnail(thumbnail)
	return this
}