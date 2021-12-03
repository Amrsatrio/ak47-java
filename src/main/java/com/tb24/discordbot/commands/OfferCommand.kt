package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.string
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.exec
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.api.EmbedBuilder

class OfferCommand : BrigadierCommand("offer", "Shows an info about an Epic Games Store offer.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("offer ID", string())
			.executes { execute(it.source, getString(it, "offer ID")) }
		)

	private fun execute(source: CommandSourceStack, offerId: String): Int {
		val launcherApi = source.session.getApiForOtherClient(EAuthClient.LAUNCHER_APP_CLIENT_2)
		val offerInfo = runCatching { launcherApi.catalogService.queryOffersBulk(listOf(offerId), null, null, null).exec().body()!!.values.first() }.getOrNull()
		val embed = EmbedBuilder()
			.setTitle("Offer Info")
			.populateOffer(offerInfo)
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}