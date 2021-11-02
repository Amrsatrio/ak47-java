package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.util.AwaitReactionsOptions
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.awaitReactions
import com.tb24.fn.DefaultInterceptor
import net.dv8tion.jda.api.EmbedBuilder

class AboutCommand : BrigadierCommand("about", "Shows credits of this bot.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val selfUser = it.source.message.jda.selfUser
			val embed = EmbedBuilder()
				.setTitle("${selfUser.name} Kotlin/JVM Rewrite v${DiscordBot.VERSION}")
				.addField("Developers", "kemo, amrsatrio", false)
				.addField("Helpers", "a.bakedpotato, Beat YT, FunGames, HYPEX, Liimiitz, Officer, Sprayxe", false)
				.addField("Third party libraries", arrayOf(
					"Brigadier",
					"ExpiringMap",
					"Guava",
					"GSON",
					"JDA",
					"JFortniteParse",
					"Log4j",
					"OkHttp",
					"RethinkDB",
					"Retrofit",
					"SLF4J",
				).joinToString("\n"), false)
				.setThumbnail(selfUser.avatarUrl)
				.setColor(0x1DE9B6)
			val message = it.source.complete(null, embed.build())
			if (message.awaitReactions({ _, _, _ -> true }, AwaitReactionsOptions().apply {
					max = 1
					time = 10000L
				}).await().isNotEmpty()) {
				message.editMessage(embed.addField("User agent", DefaultInterceptor.userAgent, false).build()).complete()
			}
			Command.SINGLE_SUCCESS
		}
}