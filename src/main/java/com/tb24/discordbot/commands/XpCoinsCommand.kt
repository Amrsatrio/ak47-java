package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.MapProcessor
import com.tb24.discordbot.Rune
import com.tb24.fn.util.Formatters
import com.tb24.uasset.AssetManager
import com.tb24.uasset.JWPSerializer
import java.io.File
import java.io.FileWriter

class GenXpCoinsDataCommand : BrigadierCommand("genxpcoinsdata", "Generate XP coins data based on the current loaded game files.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { Rune.isBotDevOrPotato(it) && Rune.hasAssetsLoaded(it) }
		.executes { c ->
			c.source.loading("Generating XP coins data")
			val start = System.currentTimeMillis()
			val entries = MapProcessor(AssetManager.INSTANCE.provider).processMap()
			FileWriter(File("./config/xp_coins_data.json").apply { parentFile.mkdirs() }).use {
				JWPSerializer.GSON.toJson(entries, it)
			}
			c.source.complete("✅ XP coins data has been successfully generated in `${Formatters.num.format(System.currentTimeMillis() - start)}ms`. Enjoy :)")
			Command.SINGLE_SUCCESS
		}
}