package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.ChannelsManager.ColorIndex
import com.tb24.fn.util.Utils
import net.dv8tion.jda.api.EmbedBuilder
import java.awt.Color

class AvatarCommand : BrigadierCommand("avatar", "Manage your Party Hub avatar.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			val avatarKeys = source.session.channelsManager.getUserSettings(source.api.currentLoggedIn.id, "avatar", "avatarBackground")
			source.complete(null, EmbedBuilder()
				.setTitle("Your Avatar")
				.setImage("https://cdn2.unrealengine.com/Kairos/portraits/${avatarKeys[0]}.png")
				.setColor(Color.decode(Utils.DEFAULT_GSON.fromJson(avatarKeys[1], Array<String>::class.java)[ColorIndex.DARK.ordinal]))
				.build())
			Command.SINGLE_SUCCESS
		}
	/*.then(literal("set")
		.executes {
			val source = it.source
			source.ensureSession()
//				source.api.channelsService.UpdateUserSetting(source.api.currentLoggedIn.id, "")
			Command.SINGLE_SUCCESS
		}
	)*/
}