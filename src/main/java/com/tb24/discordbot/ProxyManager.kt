package com.tb24.discordbot

import com.google.gson.reflect.TypeToken
import com.tb24.fn.EpicApi
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.*
import java.util.concurrent.TimeUnit

class ProxyManager {
	companion object {
		val LOGGER: Logger = LoggerFactory.getLogger("ProxyManager")
		val PROXY_AUTHENTICATOR = Authenticator { route, response ->
			val builder = response.request().newBuilder()
			val proxyUsername = BotConfig.get().proxyUsername
			val proxyPassword = BotConfig.get().proxyPassword
			if (proxyUsername != null && proxyPassword != null) {
				builder.header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
			}
			builder.build()
		}
	}

	private var usableHosts = mutableListOf<String>()

	init {
		val file = BotConfig.get().proxyHostsFile?.let { File(it) }
		if (file?.exists() != true) {
			LOGGER.info("No proxies to setup from")
		} else {
			val hostsData: Map<String, List<String>> = EpicApi.GSON.fromJson(FileReader(file), object : TypeToken<Map<String, List<String>>>() {}.type)
			val regionHosts = hostsData.flatMap { it.value }
			LOGGER.info("Found ${regionHosts.size} hosts in $file, testing them")
			for ((index, host) in regionHosts.withIndex()) {
				LOGGER.info("Testing $host (${index + 1} of ${regionHosts.size})")
				val client = OkHttpClient.Builder()
					.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(normalizeDomain(host), BotConfig.get().proxyPort)))
					.proxyAuthenticator(PROXY_AUTHENTICATOR)
					.connectTimeout(3L, TimeUnit.SECONDS)
					.build()
				try {
					@Suppress("HttpUrlsUsage")
					client.newCall(Request.Builder().url("http://www.gstatic.com/generate_204").build()).execute()
					usableHosts.add(host)
				} catch (e: IOException) {
					LOGGER.warn("$host failed: $e")
				}
			}
			LOGGER.info("${usableHosts.size} usable hosts")
		}
	}

	fun pickOne(random: Random) = if (usableHosts.isNotEmpty()) normalizeDomain(usableHosts[random.nextInt(usableHosts.size)]) else null

	private fun normalizeDomain(s: String) = BotConfig.get().proxyDomainFormat?.format(s) ?: s
}