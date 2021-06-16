package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.format
import com.tb24.fn.model.RedeemCodePayload
import com.tb24.fn.model.catalog.StoreOffer
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.api.EmbedBuilder

class CheckCodeCommand : BrigadierCommand("checkcode", "Evaluates an Epic Games code.", arrayOf("evaluatecode")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("code", greedyString())
			.executes {
				val source = it.source
				source.ensureSession()
				source.loading("Checking code")
				val launcherApi = source.session.getApiForOtherClient(EAuthClient.LAUNCHER_APP_CLIENT_2)
				val code = getString(it, "code").replace("-", "")
				val data = launcherApi.codeRedemptionService.evaluateCode(launcherApi.currentLoggedIn.id, code).exec().body()!!
				if (data.consumptionMetadata?.offerId == null) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid code (offer ID unknown)")).create()
				}
				val codeInfo = runCatching { launcherApi.catalogService.queryOffersBulk(listOf(data.consumptionMetadata.offerId), null, null, null).exec().body()!!.values.first() }.getOrNull()
				source.complete(null, source.createEmbed()
					.setTitle("✅ Valid code")
					.populateOffer(codeInfo)
					.setFooter(code)
					.build())
				Command.SINGLE_SUCCESS
			}
		)
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
				if (lockCodeResponse.consumptionMetadata?.offerId == null) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid code (offer ID unknown)")).create()
				}
				val redeemCodeResponse = launcherApi.fulfillmentService.redeemCode(launcherApi.currentLoggedIn.id, code, lockCodeResponse.codeUseId, RedeemCodePayload().apply { fulfillmentSource = "DieselWebClient" }).exec().body()!!
				val codeInfo = runCatching { launcherApi.catalogService.queryOffersBulk(listOf(lockCodeResponse.consumptionMetadata.offerId), null, null, null).exec().body()!!.values.first() }.getOrNull()
				source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
					.setTitle("✅ Code redeemed")
					.populateOffer(codeInfo)
					.setFooter(code)
					.build())
				Command.SINGLE_SUCCESS
			}
		)
}

fun EmbedBuilder.populateOffer(codeInfo: StoreOffer?): EmbedBuilder {
	if (codeInfo == null) {
		setDescription("Offer information unknown")
		return this
	}
	val name = codeInfo.title
	val description = codeInfo.description
	val image = codeInfo.keyImages.getOrNull(0)?.url
	val thumbnail = codeInfo.keyImages.getOrNull(2)?.url
	val status = codeInfo.status
	val created = codeInfo.creationDate.format()
	val seller = codeInfo.seller?.name ?: "Unknown"
	setDescription("**Name**: ${name}\n**Description**: ${description}\n**Seller**: ${seller}\n**Creation**: ${created}")
	setImage(image)
	setThumbnail(thumbnail)
	return this
}