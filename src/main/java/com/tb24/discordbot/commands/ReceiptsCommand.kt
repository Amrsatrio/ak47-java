package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.Rune
import com.tb24.discordbot.util.addFieldSeparate
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.attributes.CommonCoreProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile

// This is actually useless but Carbide started it all ¯\_(ツ)_/¯
class ReceiptsCommand : BrigadierCommand("receipts", "You asked for it") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::hasPremium)
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Getting receipts")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val receipts = (source.api.profileManager.getProfileData("common_core").stats.attributes as CommonCoreProfileAttributes).in_app_purchases.receipts
			//val receipts = source.api.fortniteService.receipts(source.api.currentLoggedIn.id).exec().body()!! //this returns only Epic Store purchases
			source.complete(null, source.createEmbed()
				.setTitle("Receipts")
				.addFieldSeparate("Entries", receipts.toList(), 2)
				.build())
			Command.SINGLE_SUCCESS
		}
}