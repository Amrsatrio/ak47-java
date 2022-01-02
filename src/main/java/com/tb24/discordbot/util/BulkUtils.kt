package com.tb24.discordbot.util

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.Session
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.fn.model.account.DeviceAuth
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryPublicProfile
import java.util.concurrent.CompletableFuture

fun <T> stwBulk(source: CommandSourceStack, usersLazy: Lazy<Collection<GameProfile>>?, each: (McpProfile) -> T?): List<T> {
	val users = if (usersLazy == null) {
		source.loading("Resolving users")
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		source.queryUsers_map(devices.map { it.accountId })
		devices.mapNotNull { source.userCache[it.accountId] }
	} else usersLazy.value
	if (users.isEmpty()) {
		throw SimpleCommandExceptionType(LiteralMessage("No users that we can display.")).create()
	}
	source.loading("Querying STW data for %,d user(s)".format(users.size))
	CompletableFuture.allOf(*users.map {
		source.api.profileManager.dispatchPublicCommandRequest(it, QueryPublicProfile(), "campaign")
	}.toTypedArray()).await()
	val results = mutableListOf<T>()
	for (user in users) {
		val campaign = source.api.profileManager.getProfileData(user.id, "campaign") ?: continue
		each(campaign)?.let(results::add)
	}
	return results
}

fun <T> forEachSavedAccounts(source: CommandSourceStack, devices: List<DeviceAuth>?, each: (Session) -> T): Map<String, T> {
	var devices = devices
	if (devices == null) {
		devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved logins, please make one!")).create()
		}
	}
	val results = mutableMapOf<String, T>()
	for (device in devices) {
		val result = withDevice(source, device, each)
		if (result != null) {
			results[device.accountId] = result
		}
	}
	return results
}

fun <T> withDevice(source: CommandSourceStack, device: DeviceAuth, callback: (Session) -> T, onInvalid: () -> Unit = {}): T? {
	try {
		var session = source.client.sessions[source.author.id]
		var requiresLogin = false
		if (session?.api?.currentLoggedIn?.id != device.accountId) {
			session = Session(source.client, source.author.id, false)
			requiresLogin = true
		} else {
			val verifyResponse = session!!.api.accountService.verify(null).execute()
			if (verifyResponse.code() == 401) {
				requiresLogin = true
			} else if (verifyResponse.code() != 200) {
				throw HttpException(verifyResponse)
			}
		}
		source.session = session
		if (requiresLogin) {
			session.login(source, device.generateAuthFields(), device.authClient, false)
		}
		val result = callback(session)
		if (requiresLogin) {
			source.session.logout()
		}
		return result
	} catch (e: HttpException) {
		source.queryUsers_map(setOf(device.accountId))
		val dn = source.userCache[device.accountId]?.displayName ?: device.accountId
		if (e.epicError.errorCode == "errors.com.epicgames.account.invalid_account_credentials" || e.epicError.errorCode == "errors.com.epicgames.account.account_not_active") {
			onInvalid()
			source.client.savedLoginsManager.remove(source.author.id, device.accountId)
			source.complete("$dn: Device auth is no longer valid")
		} else {
			source.client.commandManager.httpError(source, e, dn + ": " + source.errorTitle)
		}
		return null
	}
}