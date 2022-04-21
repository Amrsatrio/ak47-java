package com.tb24.discordbot.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.dispatchPublicCommandRequest
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.QueryPublicProfile
import net.dv8tion.jda.api.interactions.commands.OptionType

abstract class BrigadierCommand @JvmOverloads constructor(val name: String, val description: String, val aliases: Array<String> = emptyArray()) {
	lateinit var registeredNode: CommandNode<CommandSourceStack>

	abstract fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack>
	open fun getSlashCommand(): BaseCommandBuilder<CommandSourceStack>? = null

	open fun register(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralCommandNode<CommandSourceStack> =
		dispatcher.register(getNode(dispatcher))

	protected inline fun newRootNode() = literal(name)
	protected inline fun newCommandBuilder() = command(name, description)

	protected inline fun literal(name: String): LiteralArgumentBuilder<CommandSourceStack> =
		LiteralArgumentBuilder.literal(name)

	protected inline fun <T> argument(name: String, type: ArgumentType<T>): RequiredArgumentBuilder<CommandSourceStack, T> =
		RequiredArgumentBuilder.argument(name, type)

	protected inline fun command(name: String, description: String) = BaseCommandBuilder<CommandSourceStack>(name, description)
	protected inline fun subcommand(name: String, description: String) = SubcommandBuilder<CommandSourceStack>(name, description)
	protected inline fun group(name: String, description: String) = SubcommandGroupBuilder<CommandSourceStack>(name, description)

	protected fun <T : ArgumentBuilder<CommandSourceStack, T>> ArgumentBuilder<CommandSourceStack, T>.withPublicProfile(func: (CommandSourceStack, McpProfile) -> Int, loadingMsg: String, profileId: String = "campaign"): T = this
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading(loadingMsg)
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
			func(source, source.api.profileManager.getProfileData(profileId))
		}
		.then(argument("user", UserArgument.users(1))
			.executes {
				val source = it.source
				source.conditionalUseInternalSession()
				val user = UserArgument.getUsers(it, "user").values.first()
				source.loading("$loadingMsg of ${user.displayName}")
				source.api.profileManager.dispatchPublicCommandRequest(user, QueryPublicProfile(), profileId).await()
				func(source, source.api.profileManager.getProfileData(user.id, profileId))
			}
		)

	protected fun <R, T : CommandBuilder<CommandSourceStack, R, T>> CommandBuilder<CommandSourceStack, R, T>.withPublicProfile(func: (CommandSourceStack, McpProfile) -> Int, loadingMsg: String, profileId: String = "campaign"): T = this
		.option(OptionType.STRING, "user", "The user to view the data of, leave blank to view your own data", argument = UserArgument.users(1))
		.executes { source ->
			val user = source.getArgument<UserArgument.Result>("user")?.getUsers(source)?.values?.first()
			if (user != null) {
				source.loading("$loadingMsg of ${user.displayName}")
				source.api.profileManager.dispatchPublicCommandRequest(user, QueryPublicProfile(), profileId).await()
				func(source, source.api.profileManager.getProfileData(user.id, profileId))
			} else {
				source.ensureSession()
				source.loading(loadingMsg)
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
				func(source, source.api.profileManager.getProfileData(profileId))
			}
		}

	companion object {
		const val COLOR_ERROR = 0xFF4526
		const val COLOR_INFO = 0x40FAA1
		const val COLOR_SUCCESS = 0x4BDA74
		const val COLOR_WARNING = 0xFFF300
	}
}