package com.tb24.discordbot.commands

import com.google.common.collect.ImmutableMap
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.HttpException
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
import kotlin.concurrent.schedule

class LoginCommand : BrigadierCommand("login", "Logs in to an Epic account.", arrayListOf("i", "signin")) {
	val timer = Timer()

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { r.table("members").get(it.author.id).run(it.client.dbConn).first() != null }
		.executes { accountPicker(it.source) }
//		.executes { deviceCode(it.source) }
		.then(argument<CommandSourceStack, String>("authorization code", greedyString())
			.executes {
				val code = getString(it, "authorization code").replace("[&/\\\\#,+()$~%.'\":*?<>{}]".toRegex(), "").replace("code", "")
				if (code.length != 32) {
					throw SimpleCommandExceptionType(LiteralMessage("That is not an authorization code.\nHere's how to use the command correctly: When you open ${Utils.login(Utils.redirect(EAuthClient.FORTNITE_IOS_GAME_CLIENT))} you will see this text:\n```json\n{\"redirectUrl\":\"https://accounts.epicgames.com/fnauth?code=*aabbccddeeff11223344556677889900*\",\"sid\":null}```You only need to input exactly the text surrounded between *'s into the command, so it becomes:\n`${it.source.prefix}login aabbccddeeff11223344556677889900`")).create()
				}
				it.source.session.login(it.source, GrantType.authorization_code, ImmutableMap.of("code", code, "token_type", "eg1"))
			}
		)

	private fun deviceCode(source: CommandSourceStack): Int {
		if (source.api.userToken != null) {
			source.session.logout(source.message)
		}
		source.loading("Preparing your login")
		val ccLoginResponse = source.api.accountService.getAccessToken(EAuthClient.FORTNITE_SWITCH_GAME_CLIENT.asBasicAuthString(), "client_credentials", emptyMap(), null).exec().body()!!
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
				source.session.login(source, GrantType.device_code, ImmutableMap.of("device_code", deviceCodeResponse.device_code, "token_type", "eg1"), EAuthClient.FORTNITE_SWITCH_GAME_CLIENT)
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
		collector.callback = object : Collector.CollectorCallback<MessageReaction> {
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

	private fun accountPicker(source: CommandSourceStack): Int {
		val devices = source.client.savedLoginsManager.getAll(source.session.id)
		if (devices.isEmpty()) {
			return authorizationCodeHint(source)
		}
		source.loading("Preparing your login")
		source.session = source.client.internalSession
		val users = source.queryUsers(devices.map { it.accountId })
		val numberEmojis = arrayOf("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "0️⃣")
		val description = mutableListOf<String>().apply {
			for (i in devices.indices) {
				val accountId = devices[i].accountId
				add("${numberEmojis[i % numberEmojis.size]} ${users.firstOrNull { it.id == accountId }?.displayName ?: accountId}")
			}
			add("✨ Login to another account")
		}
		val botMessage = source.complete(null, EmbedBuilder()
			.setTitle("Pick an account")
			.setDescription(description.joinToString("\n"))
			.setColor(0x8AB4F8)
			.build()
		).apply {
			for (i in devices.indices) {
				addReaction(numberEmojis[i % numberEmojis.size]).queue()
			}
			addReaction("✨").complete()
		}
		try {
			val choice = botMessage.awaitReactions({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
				max = 1
				time = 30000
				errors = arrayOf(CollectorEndReason.TIME)
			}).await().values.first().reactionEmote.name
			return if (choice == "✨") {
				authorizationCodeHint(source)
			} else {
				val choiceIndex = numberEmojis.indexOf(choice)
				if (!numberEmojis.indices.contains(choiceIndex)) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid input.")).create()
				}
				val deviceData = devices[choiceIndex]
				source.session = source.initialSession
				source.session.login(source, GrantType.device_auth, ImmutableMap.of("account_id", deviceData.accountId, "device_id", deviceData.deviceId, "secret", deviceData.secret, "token_type", "eg1"))
			}
		} catch (e: CollectorException) {
			throw SimpleCommandExceptionType(LiteralMessage("You didn't respond, your login request has been canceled.")).create()
		}
	}

	private fun authorizationCodeHint(source: CommandSourceStack): Int {
		source.complete(null, EmbedBuilder()
			.setTitle("📲 Log in to your Epic Games account", Utils.login(Utils.redirect(EAuthClient.FORTNITE_IOS_GAME_CLIENT)))
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
		client_credentials,
		device_auth,
		device_code,
		exchange_code,
		external_auth,
		otp,
		password,
		refresh_token,
		token_to_token
	}
}