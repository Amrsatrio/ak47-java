package com.tb24.discordbot.commands

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.commands.arguments.StringArgument2
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.format
import com.tb24.discordbot.util.jwtPayload
import com.tb24.fn.model.account.DeviceAuth
import com.tb24.fn.util.EAuthClient
import com.tb24.fn.util.getString
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.exceptions.ErrorResponseException

class DeviceAuthCommand : BrigadierCommand("devices", "Device auth operation commands.", arrayOf("device")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes(::list)
		.then(literal("list").executes(::list))
		.then(literal("create").executes { create(it.source) })
		.then(literal("remove")
			.then(argument("device ID", greedyString())
				.executes { devicesDelete(it.source, getString(it, "device ID")) }
			)
		)
		.then(literal("import")
			.executes { importFromFile(it.source) }
			.then(argument("device auth", StringArgument2.string2())
				.executes { importFromText(it.source, getString(it, "device auth")) }
				.then(argument("auth client", StringArgumentType.word())
					.executes { importFromText(it.source, getString(it, "device auth"), getString(it, "auth client")) }
				)
			)
		)

	override fun getSlashCommand(): BaseCommandBuilder<CommandSourceStack>? {
		return super.getSlashCommand() // TODO
	}
}

class SaveLoginCommand : BrigadierCommand("savelogin", "Saves the current account to the bot, for easy login.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { create(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::create)
}

class DeleteSavedLoginCommand : BrigadierCommand("deletesavedlogin", "Removes the current account from the bot.", arrayOf("removesavedlogin")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		val user = source.api.currentLoggedIn
		val dbDevice = source.client.savedLoginsManager.get(source.session.id, user.id)
			?: throw SimpleCommandExceptionType(LiteralMessage("You don't have a saved login for this account (${user.displayName}).")).create()
		return devicesDelete(source, dbDevice.deviceId)
	}
}

private fun list(c: CommandContext<CommandSourceStack>): Int {
	val source = c.source
	source.ensureSession()
	val sessionId = source.session.id
	val user = source.api.currentLoggedIn
	source.loading("Getting account's saved logins")
	val response = source.api.accountService.getDeviceAuths(user.id).exec().body()!!
	val dbDevice = source.client.savedLoginsManager.get(sessionId, user.id)
	if (response.isEmpty()) {
		source.complete("No entries")
		return Command.SINGLE_SUCCESS
	}
	for (partitioned in response.toList().chunked(6)) { // TODO use pagination instead
		source.complete(partitioned.joinToString("\n") { item ->
			val line1: String
			val line2: String
			val line3: String
			var title = "**" + item.deviceId + "**"
			var platformVersion = item.deviceInfo?.os ?: "Unknown"
			var userAgent = item.userAgent
			if (item.deviceInfo != null) {
				title = "**" + (if (item.deviceInfo.type == item.deviceInfo.model) item.deviceInfo.model else (item.deviceInfo.type + ' ' + item.deviceInfo.model)) + "** \u00b7 ||" + item.deviceId + "||"
			}
			line1 = title
			line2 = "Added: " + item.created.render() + '\n' + "Last login: " + (item.lastAccess?.render() ?: "Never used this device to authenticate")
			try {
				val versions = userAgent.split(" ")
				val client = versions[0].split("/")
				userAgent = if (client[0] == "Fortnite") {
					val fortnite = client[1].split("-")
					client[0] + ' ' + fortnite[1] + " (" + fortnite[3] + ')'
				} else {
					client[0] + ' ' + client[1]
				}
				if (versions.size > 1) {
					val platform = versions[1].split("/")
					platformVersion = platform[0] + ' ' + platform[1]
				}
			} catch (ignored: Exception) {
			}
			line3 = (if (dbDevice?.deviceId == item.deviceId) "THIS DEVICE \u00b7 " else "") + platformVersion + " \u00b7 " + userAgent
			"${line1}\n${line2}\n${line3}\n"
		})
	}
	return Command.SINGLE_SUCCESS
}

private fun DeviceAuth.LocationIpDate.render() = "%s, %s (%s)".format(dateTime.format(), ipAddress, location)

private fun create(source: CommandSourceStack): Int {
	val inDMs = source.guild == null
	source.ensureSession()
	val sessionId = source.session.id
	val user = source.api.currentLoggedIn
	val dbDevices = source.client.savedLoginsManager.getAll(sessionId)
	if (dbDevices.any { it.accountId == user.id }) {
		throw SimpleCommandExceptionType(LiteralMessage("You already registered a device auth of this account.")).create()
	}
	checkCanAddDevice(source, dbDevices)
	source.loading("Creating device auth")
	val response = source.api.accountService.createDeviceAuth(user.id, null).exec().body()!!
	source.client.savedLoginsManager.put(sessionId, DeviceAuth().apply {
		accountId = response.accountId
		deviceId = response.deviceId
		secret = response.secret
		clientId = source.api.userToken.client_id
	})
	val embed = source.createEmbed().setColor(BrigadierCommand.COLOR_SUCCESS)
		.setTitle("✅ Device auth created and registered to ${source.jda.selfUser.name}")
		.setFooter("Use ${source.prefix}deletesavedlogin to reverse the process")
	if (inDMs) {
		source.complete(null, embed.populateDeviceAuthDetails(response).build())
	} else {
		try {
			val detailsMessage = source.author.openPrivateChannel()
				.flatMap { it.sendMessageEmbeds(EmbedBuilder(embed).populateDeviceAuthDetails(response).build()) }
				.complete()
			source.complete(null, embed.setDescription("[Check your DMs for details.](%s)".format(detailsMessage.jumpUrl)).build())
		} catch (e: ErrorResponseException) {
            source.complete(null, embed.setDescription("We couldn't DM you the details.").build())
        }
	}
	return Command.SINGLE_SUCCESS
}

private fun EmbedBuilder.populateDeviceAuthDetails(deviceAuth: DeviceAuth) =
	this.addField("Account ID", deviceAuth.accountId, false)
		.addField("Device ID", deviceAuth.deviceId, false)
		.addField("Secret (Do not share!)", "||" + deviceAuth.secret + "||", false)

fun devicesDelete(source: CommandSourceStack, deviceId: String): Int {
	if (deviceId.length != 32) {
		throw SimpleCommandExceptionType(LiteralMessage("The device ID should be a 32 character hexadecimal string")).create()
	}
	source.ensureSession()
	val sessionId = source.session.id
	val user = source.api.currentLoggedIn
	val dbDevice = source.client.savedLoginsManager.get(sessionId, user.id)
	source.loading("Deleting device auth")
	try {
		val embed = source.createEmbed()
		val msgs = mutableListOf<String>()
		source.api.accountService.deleteDeviceAuth(user.id, deviceId).exec()
		msgs.add("Deleted device auth from the account.")
		if (dbDevice != null && dbDevice.deviceId == deviceId) {
			source.client.savedLoginsManager.remove(sessionId, user.id)
			msgs.add("Unregistered device auth from ${source.jda.selfUser.name}.")
			if (source.api.userToken.jwtPayload?.getString("am") == "device_auth") {
				source.session.clear()
				msgs.add("✅ Logged out successfully.")
			}
		}
		source.complete(null, embed.setDescription(msgs.joinToString("\n")).build())
	} catch (e: HttpException) {
		if (dbDevice?.deviceId == deviceId && e.epicError.errorCode == "errors.com.epicgames.account.device_auth.not_found") {
			source.client.savedLoginsManager.remove(sessionId, user.id)
			throw SimpleCommandExceptionType(LiteralMessage("Your saved login is no longer valid.")).create()
		} else {
			throw e
		}
	}
	return Command.SINGLE_SUCCESS
}

private fun importFromText(source: CommandSourceStack, device: String, inAuthClient: String = "FORTNITE_IOS_GAME_CLIENT"): Int {
	if (source.message != null && source.guild?.selfMember?.hasPermission(Permission.MESSAGE_MANAGE) == true) {
		source.message!!.delete().queue()
	}
	source.loading("Saving device auth")
	val authClient = inAuthClient.replace("_", "").run {
		EAuthClient.values().firstOrNull { it.name.replace("_", "").equals(this, true) }?.clientId
			?: throw SimpleCommandExceptionType(LiteralMessage("Invalid auth client `$inAuthClient`. Valid clients are:```\n${EAuthClient.values().joinToString()}```")).create()
	}
	val split = device.split(":")
	if (split.size != 3) {
		throw SimpleCommandExceptionType(LiteralMessage("Device auth must be in this format: `\"account_id:device_id:secret\"`")).create()
	}
	if (split.any { it.length != 32 }) {
		throw SimpleCommandExceptionType(LiteralMessage("All device auth params must be 32 characters long")).create()
	}
	val dbDevices = source.client.savedLoginsManager.getAll(source.session.id)
	if (dbDevices.any { it.accountId == split[0] }) {
		throw SimpleCommandExceptionType(LiteralMessage("You already registered a device auth of this account.")).create()
	}
	checkCanAddDevice(source, dbDevices)
	source.client.savedLoginsManager.put(source.session.id, DeviceAuth().apply {
		accountId = split[0]
		deviceId = split[1]
		secret = split[2]
		clientId = authClient
	})
	val embed = EmbedBuilder().setColor(BrigadierCommand.COLOR_SUCCESS)
		.setTitle("✅ Device auth saved to ${source.jda.selfUser.name}")
		.setColor(BrigadierCommand.COLOR_SUCCESS)
	source.complete(null, embed.build())
	return Command.SINGLE_SUCCESS
}

private fun importFromFile(source: CommandSourceStack): Int {
	if (source.message != null && source.guild?.selfMember?.hasPermission(Permission.MESSAGE_MANAGE) == true) {
		source.message!!.delete().queue()
	}
	source.loading("Importing device auths")
	if (!BotConfig.get().allowUsersToCreateDeviceAuth) {
		throw SimpleCommandExceptionType(LiteralMessage("The current instance of the bot does not allow saving logins.")).create()
	}
	val bodyFile = source.message?.attachments?.firstOrNull()
		?: throw SimpleCommandExceptionType(LiteralMessage("You must attach a JSON file containing device auth(s) in { accountId, deviceId, secret, clientId } format to use this command without arguments.")).create()
	val parsedDevices = try {
		bodyFile.retrieveInputStream().await().bufferedReader().use { JsonParser.parseReader(it) }
	} catch (e: JsonSyntaxException) {
		throw SimpleCommandExceptionType(LiteralMessage("Malformed JSON: " + e.message?.substringAfter("Use JsonReader.setLenient(true) to accept malformed JSON at "))).create()
	}
	val limit = source.getSavedAccountsLimit()
	var success = 0
	val dbDevices = source.client.savedLoginsManager.getAll(source.session.id)
	if (parsedDevices.isJsonArray) {
		for (device in parsedDevices.asJsonArray) {
			if (dbDevices.size >= limit) {
				checkComplimentary(source, dbDevices, limit)
				if (success > 0) {
					throw SimpleCommandExceptionType(LiteralMessage("Maximum number of saved logins (%,d) has been reached, imported %,d device auths.".format(limit, success))).create()
				}
				throw SimpleCommandExceptionType(LiteralMessage("Maximum number of saved logins (%,d) has been reached.".format(limit))).create()
			}
			try {
				importFromJson(source, device.asJsonObject)
				success++
			} catch (e: Exception) {
				e.message?.let { it1 -> source.channel.sendMessage(it1).queue() }
			}
		}
		source.complete(null, EmbedBuilder().setColor(BrigadierCommand.COLOR_SUCCESS)
			.setTitle("✅ Imported %,d device auths".format(success))
			.build())
		return Command.SINGLE_SUCCESS
	} else if (parsedDevices.isJsonObject) {
		if (dbDevices.size >= limit) {
			checkComplimentary(source, dbDevices, limit)
			throw SimpleCommandExceptionType(LiteralMessage("Maximum number of saved logins (%,d) has been reached.".format(limit))).create()
		}
		importFromJson(source, parsedDevices.asJsonObject)
		source.complete(null, EmbedBuilder().setColor(BrigadierCommand.COLOR_SUCCESS)
			.setTitle("✅ Device auth saved to ${source.jda.selfUser.name}")
			.setColor(BrigadierCommand.COLOR_SUCCESS)
			.build())
		return Command.SINGLE_SUCCESS
	}
	throw SimpleCommandExceptionType(LiteralMessage("Malformed JSON: Expected array or object.")).create()
}

private fun importFromJson(source: CommandSourceStack, device: JsonObject) {
	val accountId = device.get("accountId").asString
	val deviceId = device.get("deviceId").asString
	val secret = device.get("secret").asString
	val authClient = device.get("authClient")?.asString ?: "FORTNITE_IOS_GAME_CLIENT"
	val clientId = authClient.replace("_", "").run {
			EAuthClient.values().firstOrNull { it.name.replace("_", "").equals(this, true) }?.clientId } ?: throw SimpleCommandExceptionType(LiteralMessage("Invalid auth client for account $accountId")).create()
	val dbDevices = source.client.savedLoginsManager.getAll(source.session.id)
	val dbDevice = dbDevices.firstOrNull { it.accountId == accountId }
	if (dbDevice != null) {
		throw SimpleCommandExceptionType(LiteralMessage("You already have this account's device auth.")).create()
	}
	source.client.savedLoginsManager.put(source.session.id, DeviceAuth().apply {
		this.accountId = accountId
		this.deviceId = deviceId
		this.secret = secret
		this.clientId = clientId
	})
}

private fun checkCanAddDevice(source: CommandSourceStack, dbDevices: List<DeviceAuth>) {
	val limit = source.getSavedAccountsLimit()
	if (dbDevices.size >= limit) {
		checkComplimentary(source, dbDevices, limit)
		throw SimpleCommandExceptionType(LiteralMessage("Maximum number of saved logins (%,d) has been reached.".format(limit))).create()
	}
	if (!BotConfig.get().allowUsersToCreateDeviceAuth) {
		throw SimpleCommandExceptionType(LiteralMessage("The current instance of the bot does not allow saving logins.")).create()
	}
}

private fun checkComplimentary(source: CommandSourceStack, dbDevices: List<DeviceAuth>, limit: Int) {
	if (dbDevices.isEmpty() && limit == 0) {
		val quotaSettings = BotConfig.get().deviceAuthQuota
		source.ensurePremium("Your Discord account must be older than %,d days in order to have %,d complimentary saved logins.\nGet %,d saved logins regardless of account age".format(
			quotaSettings.minAccountAgeInDaysForComplimentary,
			quotaSettings.maxForComplimentary,
			quotaSettings.maxForPremium
		))
		check(false) // We should never reach this point
	}
}