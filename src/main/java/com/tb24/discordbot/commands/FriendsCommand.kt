package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import com.tb24.discordbot.util.exec

class FriendsCommand : BrigadierCommand("friends", "friends operations") {
	val list = literal("list")
		.executes { list(it.source, "friends") }
		.then(literal("friends").executes { list(it.source, "friends") })
		.then(literal("incoming").executes { list(it.source, "incoming") })
		.then(literal("outgoing").executes { list(it.source, "outgoing") })
		.then(literal("suggested").executes { list(it.source, "outgoing") })
		.then(literal("blocklist").executes { list(it.source, "outgoing") })
		.build()
	val add = literal("add")
		.executes { Command.SINGLE_SUCCESS }
		.build()

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { list.command.run(it) }
		.then(list)
		.then(add)

	override fun register(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralCommandNode<CommandSourceStack> {
		val node = super.register(dispatcher)
		dispatcher.register(literal("f").redirect(list))
		return node
	}

	private fun list(source: CommandSourceStack, type: String): Int {
		val body = source.api.friendsService.queryFriends(source.api.currentLoggedIn.id, true).exec().body()!!
		return Command.SINGLE_SUCCESS
	}
}