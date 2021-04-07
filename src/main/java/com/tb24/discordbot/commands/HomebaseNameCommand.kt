package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.Rune
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.commandName
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.attributes.CommonPublicProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commonpublic.SetHomebaseName
import java.text.SimpleDateFormat

class HomebaseNameCommand : BrigadierCommand("homebasename", "Displays or changes the homebase name. (STW owning accounts only)") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::hasPremium)
		.executes { execute(it, null) }
		.then(argument("new name", greedyString())
			.executes { execute(it, getString(it, "new name")) }
		)

	private fun execute(context: CommandContext<CommandSourceStack>, newHomebaseName: String?): Int {
		val source = context.source
		source.ensureSession()
		val isQuery = newHomebaseName == null
		source.loading(if (isQuery) "Getting homebase name" else "Changing homebase name")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "common_public").await()
		val commonPublic = source.api.profileManager.getProfileData("common_public")
		val currentHomebaseName = (commonPublic.stats.attributes as CommonPublicProfileAttributes).homebase_name
		if (currentHomebaseName.isNullOrEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You haven't ever assigned a homebase name. Please complete the tutorial if you own Save the World.")).create()
		}
		if (isQuery) {
			source.complete(null, source.createEmbed()
				.setTitle("Homebase name")
				.addField("Current", currentHomebaseName, false)
				.addField("Last updated (UTC)", SimpleDateFormat().format(commonPublic.updated), false)
				.setFooter("Use '" + source.prefix + context.commandName + " <new name>' to change it.")
				.build())
		} else {
			source.api.profileManager.dispatchClientCommandRequest(SetHomebaseName().apply {
				homebaseName = newHomebaseName
			}, "common_public").await()
			source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
				.setDescription("✅ Updated homebase name to **$newHomebaseName**.")
				.build())
		}
		return Command.SINGLE_SUCCESS
	}
}