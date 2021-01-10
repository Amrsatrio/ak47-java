package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.exec
import com.tb24.fn.model.mcpprofile.attributes.AthenaProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.Formatters

class AthenaOverviewCommand : BrigadierCommand("br", "Shows your BR level of current season.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Getting BR data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val attrs = source.api.profileManager.getProfileData("athena").stats.attributes as AthenaProfileAttributes
			val inventory = source.api.fortniteService.inventorySnapshot(source.api.currentLoggedIn.id).exec().body()!!
			source.complete(null, source.createEmbed()
				.setTitle("Season " + attrs.season_num)
				.addField("Level", "%,d (%,d)".format(attrs.level, attrs.xp), false)
				.addField("Supercharged XP", "Remaining: %,d\nMultiplier: %,.2fx".format(attrs.rested_xp, attrs.rested_xp_mult), false)
				.addField("Account Level", Formatters.num.format(attrs.accountLevel), false)
				.addField("Bars", Formatters.num.format(inventory.stash["globalcash"] ?: 0), false)
				.build()) // TODO you have assets, load the season data
			Command.SINGLE_SUCCESS
		}
}