package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.commands.arguments.StringArgument2.Companion.string2
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.DeviceAuth
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.account.PinGrantInfo
import com.tb24.fn.network.AccountService.GrantType.*
import com.tb24.fn.util.EAuthClient
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageReaction
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.jodah.expiringmap.ExpiringMap
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.concurrent.schedule
import kotlin.math.min

class LoginCommand : BrigadierCommand("login", "Logs in to an Epic account.", arrayOf("i", "signin")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { accountPicker(it.source) }
		.then(literal("new").executes { startDefaultLoginFlow(it.source) })
		.then(argument("authorization code", greedyString())
			.executes { executeWithParam(it.source, getString(it, "authorization code")) }
		)

	override fun getSlashCommand() = newCommandBuilder()
		.option(OptionType.STRING, "code", "Saved account number or 32-character authorization code")
		.executes {
			val param = it.getOption("code")?.asString
			if (param != null) {
				if (param == "new") {
					startDefaultLoginFlow(it)
				} else {
					executeWithParam(it, param)
				}
			} else {
				accountPicker(it)
			}
		}

	private fun executeWithParam(source: CommandSourceStack, param: String): Int {
		val accountIndex = param.toIntOrNull()
		return if (accountIndex != null) {
			val devices = source.client.savedLoginsManager.getAll(source.author.id)
			val deviceData = devices.safeGetOneIndexed(accountIndex)
			doDeviceAuthLogin(source, deviceData, usedAccountNumber = true)
		} else {
			//checkRestriction(source)
			doLogin(source, EGrantType.authorization_code, param, EAuthClient.FORTNITE_ANDROID_GAME_CLIENT)
		}
	}

	private fun checkRestriction(source: CommandSourceStack) {
		val message = source.message
		if (BotConfig.get().slashCommandsEnabled && message != null && source.guild != null) {
			message.reply("⚠ Please start using the new `/login` slash command to log in. Using the legacy command could let someone else access your account if the bot fails to handle your code in time.").queue()
		}
	}
}

class ExtendedLoginCommand : BrigadierCommand("loginx", "Login with arbitrary parameters.", arrayOf("lx")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { extendedLogin(it.source) }
		.then(argument("method", word())
			.executes { extendedLogin(it.source, getString(it, "method")) }
			.then(argument("params", string2())
				.executes { extendedLogin(it.source, getString(it, "method"), getString(it, "params")) }
				.then(argument("auth client", word())
					.executes { extendedLogin(it.source, getString(it, "method"), getString(it, "params"), getString(it, "auth client")) }
				)
			)
		)

	private fun extendedLogin(source: CommandSourceStack, inGrantType: String? = null, params: String = "", inAuthClient: String? = null): Int {
		val grantType = inGrantType?.replace("_", "")?.run {
			EGrantType.values().firstOrNull { it.name.replace("_", "").equals(this, true) }
				?: throw SimpleCommandExceptionType(LiteralMessage("Invalid grant type `$inGrantType`. Valid types are:```\n${EGrantType.values().joinToString()}```")).create()
		}
		val authClient = inAuthClient?.replace("_", "")?.run {
			EAuthClient.values().firstOrNull { it.name.replace("_", "").equals(this, true) }
				?: throw SimpleCommandExceptionType(LiteralMessage("Invalid auth client `$inAuthClient`. Valid clients are:```\n${EAuthClient.values().joinToString()}```")).create()
		}
		return doLogin(source, grantType ?: EGrantType.device_code, params, authClient)
	}
}

fun doLogin(source: CommandSourceStack, grantType: EGrantType, params: String, authClient: EAuthClient?): Int {
	if (grantType != EGrantType.device_code && params.isEmpty()) {
		throw SimpleCommandExceptionType(LiteralMessage("The login method $grantType cannot be used without a parameter.")).create()
	}
	var params = params
	return when (grantType) {
		EGrantType.authorization_code -> {
			params = extractCode(params)
			if (params.length != 32) {
				throw SimpleCommandExceptionType(LiteralMessage("We did not find a 32 character hexadecimal code to log you in. Please follow the instructions again carefully.")).create()
			}
			source.session.login(source, authorizationCode(params), authClient ?: EAuthClient.FORTNITE_ANDROID_GAME_CLIENT)
		}
		EGrantType.device_auth -> {
			val split = params.split(":")
			if (split.size != 3) {
				throw SimpleCommandExceptionType(LiteralMessage("Login arguments for device auth must be in this format: `account_id:device_id:secret`")).create()
			}
			if (source.message != null && source.guild?.selfMember?.hasPermission(Permission.MESSAGE_MANAGE) == true) {
				source.message!!.delete().queue()
			}
			source.session.login(source, deviceAuth(split[0], split[1], split[2]), authClient ?: EAuthClient.FORTNITE_IOS_GAME_CLIENT)
		}
		EGrantType.device_code -> deviceCode(source, authClient ?: EAuthClient.FORTNITE_NEW_SWITCH_GAME_CLIENT)
		EGrantType.exchange_code -> {
			params = extractCode(params)
			if (params.length != 32) {
				throw SimpleCommandExceptionType(LiteralMessage("That is not an exchange code.")).create()
			}
			source.session.login(source, exchangeCode(params), authClient ?: EAuthClient.FORTNITE_ANDROID_GAME_CLIENT)
		}
		EGrantType.external_auth -> {
			val split = params.split(":")
			if (split.size != 2) {
				throw SimpleCommandExceptionType(LiteralMessage("Login arguments for device auth must be in this format: `auth_type:auth_code`")).create()
			}
			source.session.login(source, externalAuth(split[0], split[1]), authClient ?: EAuthClient.FORTNITE_ANDROID_GAME_CLIENT)
		}
		EGrantType.refresh_token -> {
			source.session.login(source, refreshToken(params), authClient ?: EAuthClient.FORTNITE_ANDROID_GAME_CLIENT)
		}
		EGrantType.token_to_token -> {
			source.session.login(source, tokenToToken(params), authClient ?: EAuthClient.FORTNITE_ANDROID_GAME_CLIENT)
		}
	}
}

@JvmField
val EPIC_HEX_PATTERN = Pattern.compile(".*([0-9a-f]{32}).*")

private fun extractCode(s: String): String {
	val matcher = EPIC_HEX_PATTERN.matcher(s)
	return if (matcher.matches()) matcher.group(1) else ""
}

private inline fun accountPicker(source: CommandSourceStack): Int {
	val devices = source.client.savedLoginsManager.getAll(source.author.id)
	if (devices.isEmpty()) {
		return startDefaultLoginFlow(source)
	}
	source.loading("Preparing your login")
	source.session = source.client.internalSession
	val users = source.queryUsers(devices.map { it.accountId })
	return if (devices.size > 25 - 1) {
		accountPicker_prompt(source, devices, users)
	} else {
		accountPicker_buttons(source, devices, users)
	}
}

private inline fun accountPicker_buttons(source: CommandSourceStack, devices: List<DeviceAuth>, users: List<GameProfile>): Int {
	val buttons = mutableListOf<Button>()
	devices.mapIndexedTo(buttons) { i, device ->
		val accountId = device.accountId
		Button.primary(accountId, "%,d. %s".format(i + 1, users.firstOrNull { it.id == accountId }?.displayName ?: accountId))
	}
	buttons.add(Button.secondary("new", "Login to another account").withEmoji(Emoji.fromUnicode("✨")))
	val botMessage = source.complete("**Pick an account**", null, *buttons.chunked(5, ActionRow::of).toTypedArray())
	val choice = botMessage.awaitOneInteraction(source.author).componentId
	source.session = source.initialSession
	return if (choice == "new") {
		startDefaultLoginFlow(source)
	} else {
		val device = devices.firstOrNull { it.accountId == choice }
			?: throw SimpleCommandExceptionType(LiteralMessage("Invalid input.")).create()
		doDeviceAuthLogin(source, device, lazy { users })
	}
}

private inline fun accountPicker_prompt(source: CommandSourceStack, devices: List<DeviceAuth>, users: List<GameProfile>): Int {
	val description = devices.mapIndexed { i, device ->
		val accountId = device.accountId
		"`${Formatters.num.format(i + 1)}` ${users.firstOrNull { it.id == accountId }?.displayName ?: accountId}"
	}
	source.complete(null, EmbedBuilder().setColor(0x8AB4F8)
		.setTitle("Pick an account")
		.setDescription(description.joinToString("\n"))
		.setFooter("Type the account number within 30 seconds to log in")
		.build())
	val choice = source.channel.awaitMessages({ _, user, _ -> user == source.author }, AwaitMessagesOptions().apply {
		max = 1
		time = 30000
		errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
	}).await().first().contentRaw.toIntOrNull()
		?: throw SimpleCommandExceptionType(LiteralMessage("The provided choice is not a number.")).create()
	val selectedDevice = devices.getOrNull(choice - 1)
		?: throw SimpleCommandExceptionType(LiteralMessage("Invalid choice.")).create()
	source.session = source.initialSession
	return doDeviceAuthLogin(source, selectedDevice, lazy { users })
}

fun doDeviceAuthLogin(source: CommandSourceStack, deviceData: DeviceAuth, users: Lazy<List<GameProfile>> = lazy {
	source.session = source.client.internalSession
	source.queryUsers(Collections.singleton(deviceData.accountId))
}, sendMessages: Boolean = true, usedAccountNumber: Boolean = false): Int {
	try {
		return source.session.login(source, deviceData.generateAuthFields(), deviceData.authClient, sendMessages, usedAccountNumber)
	} catch (e: HttpException) {
		if (e.epicError.errorCode == "errors.com.epicgames.account.invalid_account_credentials" || e.epicError.errorCode == "errors.com.epicgames.account.account_not_active") {
			val accountId = deviceData.accountId
			source.client.savedLoginsManager.remove(source.session.id, accountId)
			throw SimpleCommandExceptionType(LiteralMessage("The saved login for **${users.value.firstOrNull { it.id == accountId }?.displayName ?: accountId}** is no longer valid.\nError: ${e.epicError.displayText}")).create()
		}
		throw e
	}
}

private inline fun startDefaultLoginFlow(source: CommandSourceStack) =
	authorizationCodeHint(source, EAuthClient.FORTNITE_ANDROID_GAME_CLIENT)
	//deviceCode(source, EAuthClient.FORTNITE_NEW_SWITCH_GAME_CLIENT)

fun deviceCode(source: CommandSourceStack, authClient: EAuthClient): Int {
	//if (true) throw SimpleCommandExceptionType(LiteralMessage("Device code is disabled until further notice.")).create()
	val timer = Timer()
	if (source.api.userToken != null) {
		source.session.logout()
	}
	source.loading("Preparing your login")
	val ccLoginResponse = source.api.accountService.getAccessToken(authClient.asBasicAuthString(), clientCredentials(), "eg1", null).exec().body()!!
	/*val deviceCodeResponse = source.client.okHttpClient.newCall(Request.Builder()
		.url("https://api.epicgames.dev/epic/oauth/v1/deviceAuthorization")
		.header("Authorization", ccLoginResponse.token_type + ' ' + ccLoginResponse.access_token)
		.post(RequestBody.create(MediaType.get("application/x-www-form-urlencoded"), "prompt=login"))*/
	val deviceCodeResponse = source.client.okHttpClient.newCall(source.api.accountService.initiatePinAuth("login")
		.request().newBuilder()
		.header("Authorization", ccLoginResponse.token_type + ' ' + ccLoginResponse.access_token)
		.build()).exec().to<PinGrantInfo>()
	deviceCodeResponse.expiration = System.currentTimeMillis() + min(300L, deviceCodeResponse.expires_in) * 1000L
	source.complete(null, EmbedBuilder()
		.setTitle("📲 Open this link to log in.", deviceCodeResponse.verification_uri_complete)
		.setDescription("""— OR —
1. Visit ${deviceCodeResponse.verification_uri}
2. Enter the following code:```${deviceCodeResponse.user_code}```Stay on this screen while you sign in, it will refresh once you are done.
""")
		.setFooter("Expires")
		.setTimestamp(Instant.ofEpochMilli(deviceCodeResponse.expiration))
		.setColor(0x8AB4F8)
		.build())
	val fut = CompletableFuture<Boolean>()
	val task = timer.schedule(deviceCodeResponse.interval * 1000L, deviceCodeResponse.interval * 1000L) {
		try {
			source.session.login(source, deviceCode(deviceCodeResponse.device_code), authClient)
			fut.complete(true)
			cancel()
		} catch (e: Exception) {
			if (e is HttpException && e.epicError.errorCode == "errors.com.epicgames.account.oauth.authorization_pending") {
				if (System.currentTimeMillis() >= deviceCodeResponse.expiration) {
					fut.completeExceptionally(SimpleCommandExceptionType(LiteralMessage("The code has expired. Please do the command again.")).create())
					cancel()
				} else {
					source.loading("Waiting for your action...\n⏱ ${StringUtil.formatElapsedTime(deviceCodeResponse.expiration - System.currentTimeMillis(), true)}")
				}
				return@schedule
			}
			fut.completeExceptionally(e)
			cancel()
		}
	}
	val waitingMsg = source.loading("Waiting for your action...\n⏱ ${StringUtil.formatElapsedTime(deviceCodeResponse.expiration - System.currentTimeMillis(), true)}")!!
	waitingMsg.addReaction("❌").queue()
	val collector = waitingMsg.createReactionCollector({ reaction, user, _ -> reaction.reactionEmote.name == "❌" && user?.idLong == source.author.idLong }, ReactionCollectorOptions().apply { max = 1 })
	collector.callback = object : CollectorListener<MessageReaction> {
		override fun onCollect(item: MessageReaction, user: User?) {
			task.cancel()
			source.loading("Cancelling")
			try {
				source.client.okHttpClient.newCall(source.api.accountService.cancelPinAuth(deviceCodeResponse.user_code)
					.request().newBuilder()
					.header("Authorization", ccLoginResponse.token_type + ' ' + ccLoginResponse.access_token)
					.build()).exec()
			} catch (ignored: HttpException) {
			}
			source.complete("👌 Canceled.")
		}

		override fun onRemove(item: MessageReaction, user: User?) {}

		override fun onDispose(item: MessageReaction, user: User?) {}

		override fun onEnd(collected: Map<Any, MessageReaction>, reason: CollectorEndReason) {
			if (waitingMsg.member?.hasPermission(Permission.MESSAGE_MANAGE) == true) {
				waitingMsg.clearReactions().queue()
			}
		}
	}
	fut.await()
	collector.stop()
	return Command.SINGLE_SUCCESS
}

val existingAuthCodeHintMessages = ExpiringMap.builder()
	.expiration(3, TimeUnit.MINUTES)
	.build<String, Message>()

fun authorizationCodeHint(source: CommandSourceStack, authClient: EAuthClient): Int {
	val trackingKey = source.author.id + ':' + source.channel.id
	existingAuthCodeHintMessages[trackingKey]?.let {
		source.complete(null, EmbedBuilder().setColor(0x8AB4F8)
			.setDescription("Please check [the message above](${it.jumpUrl}) for instructions.")
			.build())
		return 0
	}
	val link = Utils.login(Utils.redirect(authClient))
	val embed = EmbedBuilder()
		.setTitle("📲 Log in to your Epic Games account", link)
		.setDescription("""
⚠ **We recommend to only log into accounts that you have email access to!**
1. Visit the link above to get your login code.
2. Copy the the entire text. Will be valid for 30 seconds, reload to get a new code.
3. Return to Discord and click on the button below.
4. Paste the whole text into the "Code" text field, and click Submit.""")
		.addField("Need to switch accounts?", "[Open this link instead]($link&prompt=login)", false)
		.setColor(0x8AB4F8)
	existingAuthCodeHintMessages[trackingKey] = source.complete(null, embed.build(), ActionRow.of(Button.secondary("submitAuthCode", "Submit code and log in...")))
	return Command.SINGLE_SUCCESS
}

enum class EGrantType {
	authorization_code,
	//client_credentials,
	device_auth,
	device_code,
	exchange_code,
	external_auth,
	//otp,
	//password,
	refresh_token,
	token_to_token
}
