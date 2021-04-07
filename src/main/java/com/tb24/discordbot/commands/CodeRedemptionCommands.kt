package com.tb24.discordbot.commands

import com.google.common.collect.ImmutableMap
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.Session
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.format
import com.tb24.fn.model.RedeemCodePayload
import com.tb24.fn.util.EAuthClient

class CheckCodeCommand : BrigadierCommand("checkcode", "Evaluates an Epic Games code.", arrayOf("evaluatecode")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("code", greedyString())
			.executes {
				val source = it.source
				source.ensureSession()
				source.loading("Checking code")
				val session = Session(source.client, source.author.id, false)
				session.login(source, GrantType.exchange_code, ImmutableMap.of("exchange_code", source.api.accountService.exchangeCode.exec().body()!!.code, "token_type", "eg1"), EAuthClient.LAUNCHER_APP_CLIENT_2, false)
				val code = getString(it, "code").replace("-", "")
				val data = session.api.codeRedemptionService.evaluateCode(session.api.currentLoggedIn.id, code).exec().body()!!
				if (data.consumptionMetadata?.offerId == null) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid code (offer ID unknown)")).create()
				}
				val embed = source.createEmbed().setTitle("✅ Valid code").setFooter(code)
				val codeInfo = runCatching { session.api.catalogService.queryOffersBulk(listOf(data.consumptionMetadata.offerId), null, null, null).exec().body()!!.values.first() }.getOrNull()
				if (codeInfo != null) {
					val name = codeInfo.title
					val description = codeInfo.description
					val image = codeInfo.keyImages.getOrNull(0)?.url
					val thumbnail = codeInfo.keyImages.getOrNull(2)?.url
					val status = codeInfo.status
					val created = codeInfo.creationDate.format()
					val owner = codeInfo.seller?.name ?: "Unknown";
					embed.setDescription("**Name**: ${name}\n**Description**: ${description}\n**Owner**: ${owner}\n**Status**: ${status}\n**Creation**: ${created}")
					embed.setImage(image)
					embed.setThumbnail(thumbnail)
				}
				source.complete(null, embed.build())
				session.logout(null)
				Command.SINGLE_SUCCESS
			}
		)
}

class RedeemCodeCommand : BrigadierCommand("redeem", "Redeems an Epic Games code.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("code", greedyString())
			.executes {
				val source = it.source
				source.ensureSession()
				source.loading("Redeeming code")
				val session = Session(source.client, source.author.id, false)
				session.login(source, GrantType.exchange_code, ImmutableMap.of("exchange_code", source.api.accountService.exchangeCode.exec().body()!!.code, "token_type", "eg1"), EAuthClient.LAUNCHER_APP_CLIENT_2, false)
				val code = getString(it, "code").replace("-", "")
				val lockCodeResponse = session.api.codeRedemptionService.lockCode(code).exec().body()!!
				if (lockCodeResponse.consumptionMetadata?.offerId == null) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid code (offer ID unknown)")).create()
				}
				val redeemCodeResponse = session.api.fulfillmentService.redeemCode(session.api.currentLoggedIn.id, code, lockCodeResponse.codeUseId, RedeemCodePayload().apply { fulfillmentSource = "DieselWebClient" }).exec().body()!!
				val embed = source.createEmbed().setTitle("✅ Code redeemed").setColor(COLOR_SUCCESS).setFooter(code)
				val codeInfo = runCatching { session.api.catalogService.queryOffersBulk(listOf(lockCodeResponse.consumptionMetadata.offerId), null, null, null).exec().body()!!.values.first() }.getOrNull()
				if (codeInfo != null) {
					val name = codeInfo.title
					val description = codeInfo.description
					val image = codeInfo.keyImages.getOrNull(0)?.url
					val thumbnail = codeInfo.keyImages.getOrNull(2)?.url
					val status = codeInfo.status
					val created = codeInfo.creationDate.format()
					val owner = codeInfo.seller?.name ?: "Unknown";
					embed.setDescription("**Name**: ${name}\n**Description**: ${description}\n**Owner**: ${owner}\n**Status**: ${status}\n**Creation**: ${created}")
					embed.setImage(image)
					embed.setThumbnail(thumbnail)
				}
				source.complete(null, embed.build())
				session.logout(null)
				Command.SINGLE_SUCCESS
			}
		)
}