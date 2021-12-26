package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.AttachmentUpload
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats

// This is actually useless but Carbide started it all ¯\_(ツ)_/¯
class ReceiptsCommand : BrigadierCommand("receipts", "You asked for it") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensurePremium("View receipts")
			source.ensureSession()
			source.loading("Getting receipts")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val commonCore = source.api.profileManager.getProfileData("common_core")
			val receipts = (commonCore.stats as CommonCoreProfileStats).in_app_purchases?.receipts
			if (receipts.isNullOrEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage("You have no past Fortnite real money transactions or code redemptions.")).create()
			}
			//val receipts = source.api.fortniteService.receipts(source.api.currentLoggedIn.id).exec().body()!! //this returns only Epic Store purchases
			source.complete(AttachmentUpload(receipts.joinToString("\n").toByteArray(), "Receipts-%s-%d.txt".format(source.api.currentLoggedIn.displayName, commonCore.rvn)))
			Command.SINGLE_SUCCESS
		}
}