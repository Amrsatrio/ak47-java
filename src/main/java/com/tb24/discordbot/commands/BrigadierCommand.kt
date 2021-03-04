package com.tb24.discordbot.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.dispatchPublicCommandRequest
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.QueryPublicProfile

abstract class BrigadierCommand @JvmOverloads constructor(val name: String, val description: String, val aliases: Array<String> = emptyArray()) {
	lateinit var registeredNode: CommandNode<CommandSourceStack>

	abstract fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack>

	open fun register(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralCommandNode<CommandSourceStack> =
		dispatcher.register(getNode(dispatcher))

	protected inline fun newRootNode() = literal(name)

	protected inline fun literal(name: String): LiteralArgumentBuilder<CommandSourceStack> =
		LiteralArgumentBuilder.literal(name)

	protected inline fun <T> argument(name: String, type: ArgumentType<T>): RequiredArgumentBuilder<CommandSourceStack, T> =
		RequiredArgumentBuilder.argument(name, type)

	protected fun <T : ArgumentBuilder<CommandSourceStack, T>> ArgumentBuilder<CommandSourceStack, T>.withPublicProfile(func: (CommandContext<CommandSourceStack>, McpProfile) -> Int, loadingMsg: String, profileId: String = "campaign"): T = this
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading(loadingMsg)
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
			func(it, source.api.profileManager.getProfileData(profileId))
		}
		.then(argument("user", UserArgument.users(1))
			.executes {
				val source = it.source
				if (source.api.userToken == null) {
					source.session = source.client.internalSession
				}
				val user = UserArgument.getUsers(it, "user").values.first()
				source.loading("$loadingMsg of ${user.displayName}")
				source.api.profileManager.dispatchPublicCommandRequest(user, QueryPublicProfile(), profileId).await()
				func(it, source.api.profileManager.getProfileData(user.id, profileId))
			}
		)

	companion object {
		const val COLOR_ERROR = 0xFF4526
		const val COLOR_INFO = 0x40FAA1
		const val COLOR_SUCCESS = 0x4BDA74
		const val COLOR_WARNING = 0xFFF300
	}
}