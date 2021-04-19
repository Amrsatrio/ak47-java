package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.exec
import com.tb24.fn.util.EAuthClient

class LaunchCommand : BrigadierCommand("launch", "Launches/logs you into Fortnite game client.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.loading("Generating exchange code")
			val code = source.api.accountService.getExchangeCode().exec().body()!!.code
			val bat = "\"C:\\Program Files\\Epic Games\\Fortnite\\FortniteGame\\Binaries\\Win64\\FortniteLauncher.exe\" -AUTH_LOGIN=unused -AUTH_PASSWORD=$code -AUTH_TYPE=exchangecode -epicapp=Fortnite -epicenv=Prod -epicportal"
			source.complete("__**Log in to Fortnite Windows as ${source.api.currentLoggedIn.displayName}**__\nCopy and paste the text below into the Run box (Win+R). Valid for 5 minutes, until it's used, or until you log out.\n```batch\n$bat\n```")
			Command.SINGLE_SUCCESS
		}
		.then(literal("android")
			.executes {
				val source = it.source
				source.loading("Generating exchange code")
				val code = source.api.accountService.getExchangeCode().exec().body()!!.code
				val link = "https://www.epicgames.com/id/exchange?exchangeCode=$code&clientId=${EAuthClient.FORTNITE_ANDROID_GAME_CLIENT.clientId}&responseType=code"
				source.complete("__**Log in to Fortnite Android as ${source.api.currentLoggedIn.displayName}**__\n1. In the game's login screen, select \"YES, Find my account\" and immediately return to Discord.\n2. Open the link below. Valid for 5 minutes, until it's used, or until you log out.\n$link")
				Command.SINGLE_SUCCESS
			}
		)
}