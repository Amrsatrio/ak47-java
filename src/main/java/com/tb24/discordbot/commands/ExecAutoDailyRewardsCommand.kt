package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder

class ExecAutoDailyRewardsCommand : BrigadierCommand("execautodaily", "Manually execute the auto claim task.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { it.author.idLong == 624299014388711455L }
		.executes {
			it.source.loading("Executing the task now")
			it.source.client.autoLoginRewardTask.run()
			it.source.complete("✅ Task executed successfully with no failures.")
			Command.SINGLE_SUCCESS
		}
}