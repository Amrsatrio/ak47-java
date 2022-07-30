package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.util.getString
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle

class LogoutCommand : BrigadierCommand("logout", "Logs out the Epic account.", arrayOf("o", "signout")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		val user = source.api.currentLoggedIn
		val dbDevice = source.client.savedLoginsManager.get(source.session.id, user.id)
		if (dbDevice != null && source.api.userToken.jwtPayload?.getString("am") == "device_auth") {
			val prompt = source.complete(null, source.createEmbed()
				.setTitle("Also remove the account?")
				.setDescription("If you do nothing within 15 seconds, we will only log you out.")
				.build(), ActionRow.of(Button.primary("positive", "Yes, remove and log out"), Button.primary("negative", "No, just log out (15s)"), Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel", Emoji.fromUnicode("❌"))))
			source.loadingMsg = prompt
			val interaction = prompt.awaitMessageComponent(source, AwaitInteractionOptions().apply {
				max = 1
				time = 15000L
				errors = arrayOf(CollectorEndReason.MESSAGE_DELETE)
				finalizeComponentsOnEnd = false
			}).await().firstOrNull()?.componentId ?: "negative"
			when (interaction) {
				"positive" -> return devicesDelete(source, dbDevice.deviceId)
				"cancel" -> throw SimpleCommandExceptionType(LiteralMessage("Log out cancelled.")).create()
			}
		}
		source.session.logout(source)
		return Command.SINGLE_SUCCESS
	}
}