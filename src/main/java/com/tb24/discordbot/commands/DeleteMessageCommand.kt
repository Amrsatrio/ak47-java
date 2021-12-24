package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType

class DeleteMessageCommand : BrigadierCommand("deletemessage", "Deletes my message in your DMs.", arrayOf("delmsg")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { it.guild == null }
		.executes {
			val message = it.source.message.referencedMessage
				?: throw SimpleCommandExceptionType(LiteralMessage("Quote my message in here to delete it.")).create()
			if (message.author != it.source.jda.selfUser) {
				throw SimpleCommandExceptionType(LiteralMessage("Not my message, I can't delete it.")).create()
			}
			message.delete().complete()
			Command.SINGLE_SUCCESS
		}
}