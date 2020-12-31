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

class CheckCodeCommand : BrigadierCommand("checkcode", "") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("code", greedyString())
			.executes {
				val source = it.source
				source.ensureSession()
				val code = getString(it, "code").replace("-", "")
				if (code.length != 4 * 5) { // XXXXX-XXXXX-XXXXX-XXXXX
					throw SimpleCommandExceptionType(LiteralMessage("Invalid code")).create()
				}
				val data = source.api.codeRedemptionService.evaluateCode(source.api.currentLoggedIn.id, code).exec().body()!!
				if (data.consumptionMetadata?.offerId == null) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid code (offer ID unknown)")).create()
				}
				val embed = source.createEmbed().setTitle("✅ Valid Code").setFooter(code)
				val codeInfo = runCatching { source.api.catalogService.queryOffersBulk(listOf(data.consumptionMetadata.offerId), null, null, null).exec().body()!!.values.first() }.getOrNull()
				if (codeInfo != null) {
					val name = codeInfo.title
					val description = codeInfo.description
					val image = codeInfo.keyImages[0]?.url
					val thumbnail = codeInfo.keyImages[2]?.url
					val status = codeInfo.status
					val created = codeInfo.creationDate.format()
					val owner = codeInfo.seller?.name ?: "Unknown";
					embed.setDescription("**Name**: ${name}\n**Description**: ${description}\n**Owner**: ${owner}\n**Status**: ${status}\n**Creation**: ${created}")
					embed.setImage(image)
					embed.setThumbnail(thumbnail)
				}
				source.complete(null, embed.build())
				Command.SINGLE_SUCCESS
			}
		)
}