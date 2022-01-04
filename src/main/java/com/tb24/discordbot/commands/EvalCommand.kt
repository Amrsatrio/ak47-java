package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.Rune
import com.tb24.uasset.AssetManager
import org.graalvm.polyglot.Context

class EvalCommand : BrigadierCommand("eval", "Evaluate an expression for debugging purposes.") {
	init {
		System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
	}

	private val engine = Context.newBuilder("js").allowAllAccess(true).build().apply {
		getBindings("js").apply {
			putMember("provider", AssetManager.INSTANCE)
			putMember("r", RethinkDB.r)
		}
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::isBotDev)
		.then(argument("code", greedyString())
			.executes { handle(it.source, getString(it, "code")) }
		)

	private fun handle(source: CommandSourceStack, code: String): Int {
		if (engine == null) {
			throw SimpleCommandExceptionType(LiteralMessage("JavaScript engine is not available.")).create()
		}
		try {
			synchronized(engine) {
				engine.getBindings("js").apply {
					putMember("client", source.client)
					putMember("source", source)
					putMember("config", BotConfig.get())
					putMember("db", source.client.dbConn)
				}
				source.complete("```\n${engine.eval("js", code)}```", null)
			}
		} catch (e: Throwable) {
			throw SimpleCommandExceptionType(LiteralMessage("Execution failed```\n${e.message}```")).create()
		}
		return Command.SINGLE_SUCCESS
	}
}