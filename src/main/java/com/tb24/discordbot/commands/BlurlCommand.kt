package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.BoolArgumentType.getBool
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.Blurl
import com.tb24.discordbot.util.exec
import com.tb24.fn.EpicApi
import okhttp3.Request

class BlurlCommand : BrigadierCommand("blurl", "Fetches and decompresses a streamed video blurl for inspection.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("video ID", word())
			.executes { execute(it.source, getString(it, "video ID")) }
			.then(argument("beautify?", bool())
				.executes { execute(it.source, getString(it, "video ID"), getBool(it, "beautify?")) }
			)
		)

	private fun execute(source: CommandSourceStack, videoId: String, beautify: Boolean = false): Int {
		val blurl = source.api.okHttpClient.newCall(Request.Builder().url("http://fortnite-vod.akamaized.net/${videoId}/master.blurl").build())
			.exec().body!!.bytes()
		val builder = EpicApi.GSON.newBuilder()
		if (beautify) {
			builder.setPrettyPrinting()
		}
		val parsed = Blurl.decompress(blurl)
		source.channel.sendFile(builder.create().toJson(parsed).toByteArray(), "$videoId.json").complete()
		return Command.SINGLE_SUCCESS
	}
}