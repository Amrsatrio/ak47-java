package com.tb24.discordbot.commands

import com.google.common.collect.ImmutableMap
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.Rune
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.PinGrantInfo
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.MessageReaction
import net.dv8tion.jda.api.entities.User
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule

class LoginCommand : BrigadierCommand("login", "Logs in to an Epic account.", arrayOf("i", "signin")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::hasAccess)
		.executes { accountPicker(it.source) }
		.then(argument("authorization code", greedyString())
			.executes { doLogin(it.source, GrantType.authorization_code, getString(it, "authorization code"), EAuthClient.FORTNITE_IOS_GAME_CLIENT) }
		)
}

class ExtendedLoginCommand : BrigadierCommand("loginx", "Login with arbitrary parameters.", arrayOf("lx")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::hasAccess)
		.executes { extendedLogin(it.source) }
		.then(argument("method", word())
			.executes { extendedLogin(it.source, getString(it, "method")) }
			.then(argument("params", string())
				.executes { extendedLogin(it.source, getString(it, "method"), getString(it, "params")) }
				.then(argument("auth client", word())
					.executes { extendedLogin(it.source, getString(it, "method"), getString(it, "params"), getString(it, "auth client")) }
				)
			)
		)

	private fun extendedLogin(source: CommandSourceStack, inGrantType: String? = null, params: String = "", inAuthClient: String? = null): Int {
		val grantType = inGrantType?.replace("_", "")?.run {
			GrantType.values().firstOrNull { it.name.replace("_", "").equals(this, true) }
				?: throw SimpleCommandExceptionType(LiteralMessage("Invalid grant type `$inGrantType`. Valid types are:```\n${GrantType.values().joinToString()}```")).create()
		}
		val authClient = inAuthClient?.replace("_", "")?.run {
			EAuthClient.values().firstOrNull { it.name.replace("_", "").equals(this, true) }
				?: throw SimpleCommandExceptionType(LiteralMessage("Invalid auth client `$inAuthClient`. Valid clients are:```\n${EAuthClient.values().joinToString()}```")).create()
		}
		return doLogin(source, grantType ?: GrantType.device_code, params, authClient)
	}
}

fun doLogin(source: CommandSourceStack, grantType: GrantType, params: String, authClient: EAuthClient?): Int {
	if (grantType != GrantType.device_code && params.isEmpty()) {
		throw SimpleCommandExceptionType(LiteralMessage("The login method $grantType cannot be used without a parameter.")).create()
	}
	var params = params
	return when (grantType) {
		GrantType.authorization_code -> {
			params = params.replace("[&/\\\\#,+()$~%.'\":*?<>{}]".toRegex(), "").replace("code", "")
			if (params.length != 32) {
				throw SimpleCommandExceptionType(LiteralMessage("That is not an authorization code.\nHere's how to use the command correctly: When you open ${Utils.login(Utils.redirect(authClient))} you will see this text:\n```json\n{\"redirectUrl\":\"https://accounts.epicgames.com/fnauth?code=*aabbccddeeff11223344556677889900*\",\"sid\":null}```You only need to input exactly the text surrounded between *'s into the command, so it becomes:\n`${source.prefix}login aabbccddeeff11223344556677889900`")).create()
			}
			source.session.login(source, grantType, ImmutableMap.of("code", params, "token_type", "eg1"), authClient ?: EAuthClient.FORTNITE_IOS_GAME_CLIENT)
		}
		GrantType.device_auth -> {
			val split = params.split(":")
			if (split.size != 3) {
				throw SimpleCommandExceptionType(LiteralMessage("Login arguments for device auth must be in this format: `account_id:device_id:secret`")).create()
			}
			source.session.login(source, grantType, ImmutableMap.of("account_id", split[0], "device_id", split[1], "secret", split[2], "token_type", "eg1"), authClient ?: EAuthClient.FORTNITE_IOS_GAME_CLIENT)
		}
		GrantType.device_code -> deviceCode(source, authClient ?: EAuthClient.FORTNITE_SWITCH_GAME_CLIENT)
		GrantType.exchange_code -> {
			params = params.replace("[&/\\\\#,+()$~%.'\":*?<>{}]".toRegex(), "").replace("code", "")
			if (params.length != 32) {
				throw SimpleCommandExceptionType(LiteralMessage("That is not an exchange code.")).create()
			}
			source.session.login(source, grantType, ImmutableMap.of("exchange_code", params, "token_type", "eg1"), authClient ?: EAuthClient.FORTNITE_IOS_GAME_CLIENT)
		}
		GrantType.token_to_token -> {
			source.session.login(source, grantType, ImmutableMap.of("access_token", params, "token_type", "eg1"), authClient ?: EAuthClient.FORTNITE_IOS_GAME_CLIENT)
		}
		else -> throw SimpleCommandExceptionType(LiteralMessage("Unsupported grant type $grantType")).create()
	}
}

private fun accountPicker(source: CommandSourceStack): Int {
	val devices = source.client.savedLoginsManager.getAll(source.author.id)
	if (devices.isEmpty()) {
		return startDefaultLoginFlow(source)
	}
	val numberEmojis = arrayOf("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "0️⃣")
	check(devices.size <= numberEmojis.size)
	source.loading("Preparing your login")
	source.session = source.client.internalSession
	val users = source.queryUsers(devices.map { it.accountId })
	val description = mutableListOf<String>().apply {
		for (i in devices.indices) {
			val accountId = devices[i].accountId
			add("${numberEmojis[i]} ${users.firstOrNull { it.id == accountId }?.displayName ?: accountId}")
		}
		add("✨ Login to another account")
	}
	val botMessage = source.complete(null, EmbedBuilder()
		.setTitle("Pick an account")
		.setDescription(description.joinToString("\n"))
		.setColor(0x8AB4F8)
		.build()
	)
	val shouldStop = AtomicBoolean()
	CompletableFuture.supplyAsync {
		for (i in devices.indices) {
			if (shouldStop.get()) {
				return@supplyAsync
			}
			botMessage.addReaction(numberEmojis[i % numberEmojis.size]).complete()
		}
		if (!shouldStop.get()) {
			botMessage.addReaction("✨").complete()
		}
	}
	val choice = botMessage.awaitReactions({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
		max = 1
		time = 30000
		errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
	}).await().first().reactionEmote.name
	shouldStop.set(true)
	source.session = source.initialSession
	return if (choice == "✨") {
		startDefaultLoginFlow(source)
	} else {
		val choiceIndex = numberEmojis.indexOf(choice)
		if (!numberEmojis.indices.contains(choiceIndex)) {
			throw SimpleCommandExceptionType(LiteralMessage("Invalid input.")).create()
		}
		val deviceData = devices[choiceIndex]
		val auth = deviceData.clientId?.let(EAuthClient::getByClientId) ?: EAuthClient.FORTNITE_IOS_GAME_CLIENT
		try {
			source.session.login(source, GrantType.device_auth, ImmutableMap.of("account_id", deviceData.accountId, "device_id", deviceData.deviceId, "secret", deviceData.secret, "token_type", "eg1"), auth)
		} catch (e: HttpException) {
			if (e.epicError.errorCode == "errors.com.epicgames.account.invalid_account_credentials" || e.epicError.errorCode == "errors.com.epicgames.account.account_not_active") {
				val accountId = deviceData.accountId
				source.client.savedLoginsManager.remove(source.session.id, accountId)
				throw SimpleCommandExceptionType(LiteralMessage("The saved login for **${users.firstOrNull { it.id == accountId }?.displayName ?: accountId}** is no longer valid.\nError: ${e.epicError.displayText}")).create()
			}
			throw e
		}
	}
}

private inline fun startDefaultLoginFlow(source: CommandSourceStack) =
	//authorizationCodeHint(source, EAuthClient.FORTNITE_IOS_GAME_CLIENT)
	deviceCode(source, EAuthClient.FORTNITE_SWITCH_GAME_CLIENT)

private val timer = Timer()
fun deviceCode(source: CommandSourceStack, authClient: EAuthClient): Int {
	if (source.api.userToken != null) {
		source.session.logout(source.message)
	}
	source.loading("Preparing your login")
	val ccLoginResponse = source.api.accountService.getAccessToken(authClient.asBasicAuthString(), "client_credentials", emptyMap(), null).exec().body()!!
	val deviceCodeResponse = source.client.okHttpClient.newCall(source.api.accountService.initiatePinAuth("login")
		.request().newBuilder()
		.header("Authorization", ccLoginResponse.token_type + ' ' + ccLoginResponse.access_token)
		.build()).exec().to<PinGrantInfo>()
	deviceCodeResponse.expiration = System.currentTimeMillis() + deviceCodeResponse.expires_in * 1000L
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
			source.session.login(source, GrantType.device_code, ImmutableMap.of("device_code", deviceCodeResponse.device_code, "token_type", "eg1"), authClient)
			fut.complete(true)
			cancel()
		} catch (e: Exception) {
			if (e is HttpException && e.epicError.errorCode == "errors.com.epicgames.account.oauth.authorization_pending") {
				if (System.currentTimeMillis() >= deviceCodeResponse.expiration) {
					fut.completeExceptionally(SimpleCommandExceptionType(LiteralMessage("The code has expired. Please do the command again.")).create())
					cancel()
				} else {
					source.loading("Waiting for your action %LOADING%\n⏱ ${StringUtil.formatElapsedTime(deviceCodeResponse.expiration - System.currentTimeMillis(), true)}")
				}
				return@schedule
			}
			fut.completeExceptionally(e)
			cancel()
		}
	}
	val waitingMsg = source.loading("Waiting for your action %LOADING%\n⏱ ${StringUtil.formatElapsedTime(deviceCodeResponse.expiration - System.currentTimeMillis(), true)}")
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
			if (waitingMsg.member != null && waitingMsg.member!!.hasPermission(Permission.MESSAGE_MANAGE)) {
				waitingMsg.clearReactions().queue()
			}
		}
	}
	fut.await()
	collector.stop()
	return Command.SINGLE_SUCCESS
}

fun authorizationCodeHint(source: CommandSourceStack, authClient: EAuthClient): Int {
	source.complete(null, EmbedBuilder()
		.setTitle("📲 Log in to your Epic Games account", Utils.login(Utils.redirect(authClient)))
		.setDescription("""
1. Visit the link above to get your login code.
2. Copy the 32 character code that looks like `aabbccddeeff11223344556677889900`, located after `?code=`.
3. Send `${source.prefix}login <32 character code>` to complete your login.""")
		.setColor(0x8AB4F8)
		.build())
	return Command.SINGLE_SUCCESS
}

enum class GrantType {
	authorization_code,
	//client_credentials,
	device_auth,
	device_code,
	exchange_code,
	//external_auth,
	//otp,
	//password,
	//refresh_token,
	token_to_token
}