package com.tb24.discordbot.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder

class GiftsCommand : BrigadierCommand("gifts", "Shows your incoming gifts and reward notifications.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> {
		TODO("Not yet implemented")
	}
}