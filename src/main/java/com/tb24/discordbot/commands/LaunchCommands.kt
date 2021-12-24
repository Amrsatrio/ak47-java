package com.tb24.discordbot.commands

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.Session
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.DeviceAuth
import com.tb24.fn.util.EAuthClient
import mslinks.ShellLink
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.utils.TimeFormat
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LaunchWindowsCommand : BrigadierCommand("launch", "Launches you into Fortnite Windows using your current session.", arrayOf("launchwindows")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			val deviceData = if (true) null else source.client.savedLoginsManager.get(source.session.id, source.api.currentLoggedIn.id)
			if (deviceData != null && source.guild != null) {
				throw SimpleCommandExceptionType(LiteralMessage("Please invoke the command again [in DMs](${source.getPrivateChannelLink()}), as we have to send you info that carries over your current session.")).create()
			}
			if (source.guild != null && !source.complete(null, source.createEmbed().setColor(COLOR_WARNING)
					.setTitle("✋ Hold up!")
					.setDescription("We're about to send a code that carries your current session which will be valid for some time or until you log out. Make sure you trust the people here, or you may do the command again [in DMs](${source.getPrivateChannelLink()}).\n\nContinue?")
					.build(), confirmationButtons()).awaitConfirmation(source.author).await()) {
				source.complete("👌 Alright.")
				return@executes 0
			}
			val launcherPath = "C:\\Program Files\\Epic Games\\Fortnite\\FortniteGame\\Binaries\\Win64\\FortniteLauncher.exe"
			val commandLine = "start /d \"%s\" %s %s".format(launcherPath.substringBeforeLast('\\'), launcherPath.substringAfterLast('\\'), generateLaunchArgs(source, deviceData))
			val validityMessage = if (deviceData != null) "Valid until you delete the saved login for that account.\n⚠ **Don't share the text below, anyone can login to your account easily with it!**" else "Valid for 5 minutes, until it's used, or until you log out."
			source.complete("__**Log in to Fortnite Windows as ${source.api.currentLoggedIn.displayName}**__\nCopy and paste the text below into a Command Prompt window (cmd.exe) and hit enter. $validityMessage\n```bat\n$commandLine\n```")
			Command.SINGLE_SUCCESS
		}
		.then(literal("shortcuts")
			.executes { executeGenerateShortcuts(it.source) }
			.then(argument("Fortnite path", greedyString())
				.executes { executeGenerateShortcuts(it.source, getString(it, "Fortnite path")) }
			)
		)

	private fun executeGenerateShortcuts(source: CommandSourceStack, gamePath: String = "C:\\Program Files\\Epic Games\\Fortnite"): Int {
		throw SimpleCommandExceptionType(LiteralMessage("Shortcuts feature is disabled until further notice.")).create()
		if (source.guild != null) {
			throw SimpleCommandExceptionType(LiteralMessage("Please invoke the command again [in DMs](${source.getPrivateChannelLink()}), as we have to send you info that contains your saved logins.")).create()
		}
		gamePath.replace('/', '\\')
		if (!gamePath.endsWith("\\Fortnite")) {
			throw SimpleCommandExceptionType(LiteralMessage("Path must be a folder named **Fortnite**, not **${gamePath.substringAfterLast('\\')}**. Example: `C:\\Program Files\\Epic Games\\Fortnite`")).create()
		}
		if (source.api.userToken == null) {
			source.session = source.client.internalSession
		}
		source.loading("Generating shortcut files")
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved logins, please make one!")).create()
		}
		val users = source.queryUsers(devices.map { it.accountId })
		val os = ByteArrayOutputStream()
		ZipOutputStream(os).use { zip ->
			for (device in devices) {
				val dn = users.firstOrNull { it.id == device.accountId }?.displayName ?: device.accountId
				val exeDir = "$gamePath\\FortniteGame\\Binaries\\Win64"
				val lnk = ShellLink.createLink("$exeDir\\FortniteLauncher.exe").apply {
					cmdArgs = generateLaunchArgs(source, device)
					workingDir = exeDir
					name = "Launch Fortnite as $dn"
				}
				zip.putNextEntry(ZipEntry("$dn.lnk"))
				lnk.serialize(zip)
				zip.closeEntry()
			}
		}
		source.channel.sendFile(os.toByteArray(), "LaunchShortcuts-${source.author.name}-${source.author.discriminator}.zip").complete()
		source.loadingMsg!!.delete().queue()
		return Command.SINGLE_SUCCESS
	}

	private fun generateLaunchArgs(source: CommandSourceStack, deviceData: DeviceAuth?): String {
		val login: String
		val password: String
		val type: String
		var authClientArgs = ""
		if (deviceData != null) {
			login = deviceData.accountId
			password = deviceData.deviceId + ':' + deviceData.secret
			type = "device_auth"
			val authClient = deviceData.authClient
			if (authClient != EAuthClient.FORTNITE_PC_GAME_CLIENT) {
				authClientArgs = " -AuthClient=${authClient.clientId} -AuthSecret=${authClient.secret}"
			}
		} else {
			source.loading("Generating exchange code")
			login = "unused"
			password = source.session.getApiForOtherClient(EAuthClient.LAUNCHER_APP_CLIENT_2).accountService.getExchangeCode().exec().body()!!.code
			type = "exchangecode"
		}
		val accountId = deviceData?.accountId ?: source.api.currentLoggedIn.id
		return "-AUTH_LOGIN=$login -AUTH_PASSWORD=$password -AUTH_TYPE=$type$authClientArgs -epicapp=Fortnite -epicenv=Prod -EpicPortal -epicuserid=$accountId"
	}
}

class LaunchAndroidCommand : BrigadierCommand("launchandroid", "Log in to Fortnite Android using your current session.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Generating exchange code")
			val code = source.api.accountService.getExchangeCode().exec().body()!!.code
			val link = "https://www.epicgames.com/id/exchange?exchangeCode=$code&clientId=${EAuthClient.FORTNITE_ANDROID_GAME_CLIENT.clientId}&responseType=code"
			source.complete("__**Log in to Fortnite Android as ${source.api.currentLoggedIn.displayName}**__\n1. In the game's login screen, select \"YES, Find my account\" and immediately return to Discord.\n2. Open the link below. Valid for 5 minutes, until it's used, or until you log out.\n$link")
			Command.SINGLE_SUCCESS
		}
}