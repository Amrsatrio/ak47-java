package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.INTRO_NAME
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.commoncore.SetForcedIntroPlayed

class SkipIntroCommand : BrigadierCommand("skipintro", "Skips the forced Chapter 2, Season 6 intro on your account.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { INTRO_NAME != null }
		.executes {
			val source = it.source
			if (INTRO_NAME == null) {
				throw SimpleCommandExceptionType(LiteralMessage("There are no intros this season.")).create()
			}
			source.ensureSession()
			source.loading("Skipping intro")
			val response = source.api.profileManager.dispatchClientCommandRequest(SetForcedIntroPlayed().apply { forcedIntroName = INTRO_NAME }).await()
			if (response.profileRevision > response.profileChangesBaseRevision) {
				source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
					.setTitle("✅ Skipped intro")
					.setDescription("You will load straight into the lobby the next time you launch the game with this account.")
					.build())
			} else {
				source.complete(null, source.createEmbed().setColor(COLOR_ERROR)
					.setDescription("❌ You have already played or skipped the intro, no need to skip.")
					.build())
			}
			Command.SINGLE_SUCCESS
		}
}