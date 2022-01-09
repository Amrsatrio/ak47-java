package com.tb24.discordbot.tasks

import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.commands.claimFreeLlamas

class AutoFreeLlamaTask(client: DiscordBot) : AbstractAutoTask(client, "auto_llama") {
	override fun performForAccount(source: CommandSourceStack) = claimFreeLlamas(source)
	override fun delay() = Thread.sleep(random.nextInt(1000).toLong()) // We don't have much time
	override fun text1() = "auto free llama claiming"
	override fun text2() = "claim free llamas"
}