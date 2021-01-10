package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.getUsers
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.users
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.exec
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.util.Formatters

class AthenaInventoryCommand : BrigadierCommand("brinventory", "Shows your or other players' amount of bars.", arrayOf("bars")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source, it.source.api.currentLoggedIn) }
		.then(argument("user", users(1))
			.executes { execute(it.source, getUsers(it, "user").values.first()) }
		)

	private fun execute(source: CommandSourceStack, user: GameProfile): Int {
		source.ensureSession()
		source.loading("Getting BR global inventory data")
		val inventory = source.api.fortniteService.inventorySnapshot(user.id).exec().body()!!
		source.complete(null, source.createEmbed(user)
			.setTitle("BR Inventory")
			.setDescription(inventory.stash?.entries
				?.joinToString("\n") { it.key + ": " + Formatters.num.format(it.value) }
				?.takeIf { it.isNotEmpty() }
				?: "No entries")
			.setThumbnail(Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Athena/T-T-Icon-BR-GoldBars-UI-Icon-L.T-T-Icon-BR-GoldBars-UI-Icon-L"))
			.build())
		return Command.SINGLE_SUCCESS
	}
}