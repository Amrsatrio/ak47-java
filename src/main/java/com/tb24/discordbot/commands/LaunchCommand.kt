package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.exec
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.api.entities.ChannelType

class LaunchCommand : BrigadierCommand("launch", "Launches/logs you into Fortnite game client.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			if (!source.isFromType(ChannelType.PRIVATE)) {
				throw SimpleCommandExceptionType(LiteralMessage("Please invoke the command again in DMs, as we have to send you info that carries over your current session.")).create()
			}
			val login: String
			val password: String
			val type: String
			var authClientArgs = ""
			val deviceData = source.client.savedLoginsManager.get(source.session.id, source.api.currentLoggedIn.id)
			if (deviceData != null) {
				login = source.api.currentLoggedIn.id
				password = deviceData.deviceId + ':' + deviceData.secret
				type = "device_auth"
				val authClient = deviceData.authClient
				if (authClient != EAuthClient.FORTNITE_PC_GAME_CLIENT) {
					authClientArgs = " -AuthClient=${authClient.clientId} -AuthSecret=${authClient.secret}"
				}
			} else {
				source.loading("Generating exchange code")
				login = "unused"
				password = source.api.accountService.getExchangeCode().exec().body()!!.code
				type = "exchangecode"
			}
			val launcherPath = "C:\\Program Files\\Epic Games\\Fortnite\\FortniteGame\\Binaries\\Win64\\FortniteLauncher.exe"
			val bat = "\"$launcherPath\" -AUTH_LOGIN=$login -AUTH_PASSWORD=$password -AUTH_TYPE=$type$authClientArgs -epicapp=Fortnite -epicenv=Prod -epicportal"
			val validityMessage = if (deviceData != null) "Valid until you delete the saved login for that account.\n⚠ **Don't share the text below, anyone can login to your account easily with it!**" else "Valid for 5 minutes, until it's used, or until you log out."
			source.complete("__**Log in to Fortnite Windows as ${source.api.currentLoggedIn.displayName}**__\nCopy and paste the text below into the Windows Search box (Win+S) and hit enter. $validityMessage\n```bat\n$bat\n```")
			Command.SINGLE_SUCCESS
		}
		.then(literal("android")
			.executes {
				val source = it.source
				source.ensureSession()
				source.loading("Generating exchange code")
				val code = source.api.accountService.getExchangeCode().exec().body()!!.code
				val link = "https://www.epicgames.com/id/exchange?exchangeCode=$code&clientId=${EAuthClient.FORTNITE_ANDROID_GAME_CLIENT.clientId}&responseType=code"
				source.complete("__**Log in to Fortnite Android as ${source.api.currentLoggedIn.displayName}**__\n1. In the game's login screen, select \"YES, Find my account\" and immediately return to Discord.\n2. Open the link below. Valid for 5 minutes, until it's used, or until you log out.\n$link")
				Command.SINGLE_SUCCESS
			}
		)
}