package com.tb24.discordbot.webcampaign

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.to
import com.tb24.fn.EpicApi
import com.tb24.fn.util.getBoolean
import com.tb24.fn.util.getInt
import com.tb24.fn.util.getLong
import com.tb24.fn.util.getString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

class WebCampaign(val okHttpClient: OkHttpClient, val domainName: String) {
	companion object {
		private val LOGGER = LoggerFactory.getLogger(WebCampaign::class.java)
		private val environments = ConcurrentHashMap<String, JsonObject>()
		private val localizations = ConcurrentHashMap<String, JsonObject>()
		private val DEBUG = true
	}

	private var ws: WebSocket? = null
	private var connectionId: String? = null
	private var sequence = 0
	private val pendingResponses = ConcurrentHashMap<String, PendingResponse>()
	val states = ConcurrentHashMap<String, JsonObject>() // type:key -> state
	private var timer = Timer()
	private var autoDisconnectTask: TimerTask? = null
	var properties = JsonObject()
	var onUpdateProperties: ((JsonObject) -> Unit)? = null
	var onRequestAuth: ((JsonObject) -> Unit)? = null
	//var onStateUpdated

	val environment: JsonObject = environments.getOrPut(domainName) {
		val request = Request.Builder()
			.url("https://$domainName.fortnite.com/environment.js")
			.build()
		val response = okHttpClient.newCall(request).exec().body()!!.string()
		JsonParser.parseString(response.substringAfter("window.emconfig = ")).asJsonObject
	}

	val localization: JsonObject = localizations.getOrPut(domainName) {
		val request = Request.Builder()
			.url("https://$domainName.fortnite.com/static/locales/en-US/general.json")
			.build()
		okHttpClient.newCall(request).exec().to()
	}

	fun send(type: String, payload: JsonObject? = null): CompletableFuture<JsonObject> {
		autoDisconnectTask?.cancel()
		autoDisconnectTask = timer.schedule(5L * 60L * 1000L) { // 5 minutes idle
			disconnect()
		}
		return internalSend(type, payload)
	}

	private fun internalSend(type: String, payload: JsonObject? = null): CompletableFuture<JsonObject> {
		val data = JsonObject()
		// commandId: <current time in seconds>.<sequence>
		val thisSequence = ++sequence
		val commandId = "%s.%d".format(System.currentTimeMillis() / 1000, thisSequence)
		data.addProperty("commandId", commandId)
		if (connectionId != null) {
			data.addProperty("connectionId", connectionId)
		}
		data.addProperty("type", type)
		data.addProperty("time", thisSequence)
		if (payload != null) {
			data.add("payload", payload)
		}
		if (DEBUG) println("Send: $data")
		ws!!.send(data.toString())
		val pendingResponse = PendingResponse(commandId)
		pendingResponses[commandId] = pendingResponse
		return pendingResponse.future
	}

	private fun internalReceive(data: JsonObject) {
		val resConnectionId = data.getString("connectionId")
		if (connectionId == null) {
			connectionId = resConnectionId
		} else if (connectionId != resConnectionId) {
			LOGGER.warn("Connection ID mismatch: $resConnectionId != $connectionId")
			return
		}

		val timeOffset = System.currentTimeMillis() - data.getLong("serverTime")
		val error = data.getBoolean("error")
		val payload = data.getAsJsonObject("payload")

		val commandId = data.getString("commandId")
		if (commandId != null) {
			val pendingResponse = pendingResponses[commandId]
			if (pendingResponse != null) {
				pendingResponses.remove(commandId)
				if (!error) {
					pendingResponse.receive(payload)
				} else {
					pendingResponse.receiveError(payload)
				}
			}
		}

		if (payload != null && !error) {
			payload.getAsJsonArray("states")?.forEach { stateUpdateElm ->
				val stateUpdate = stateUpdateElm.asJsonObject
				states["%s:%s".format(stateUpdate.getString("key"), stateUpdate.getString("type"))] = stateUpdate.getAsJsonObject("state")
			}
		}

		val resProperties = data.getAsJsonObject("properties")
		if (resProperties != null) {
			properties = resProperties
			onUpdateProperties?.invoke(properties)
		}
	}

	@Synchronized
	fun connect() {
		if (ws != null) {
			return
		}
		ws = okHttpClient.newWebSocket(Request.Builder().url("wss://${environment.getString("backendHost")}/handler").build(), object : WebSocketListener() {
			override fun onMessage(webSocket: WebSocket, text: String) {
				if (DEBUG) println("Recv: $text")
				internalReceive(JsonParser.parseString(text).asJsonObject)
			}
		})
		internalSend("service.hello", JsonObject().apply { add("properties", properties) }).await()
		if (!properties.has("epicSessionToken")) {
			val myOnRequestAuth = onRequestAuth
			if (myOnRequestAuth != null) {
				val payload = JsonObject()
				myOnRequestAuth(payload)
				internalSend("account.authenticate", payload).await()
			} else {
				LOGGER.warn("No onRequestAuth callback set, will not try to authenticate")
			}
		}
		val pingInterval = 10L * 1000L
		timer.scheduleAtFixedRate(pingInterval, pingInterval) {
			internalSend("service.ping")
		}
	}

	@Synchronized
	fun disconnect() {
		if (ws == null) {
			return
		}
		if (properties.has("epicSessionToken")) {
			internalSend("account.logout").await()
		}
		timer.cancel()
		timer = Timer()
		ws!!.close(1000, null)
		ws = null
		connectionId = null
	}

	private inner class PendingResponse(commandId: String, timeout: Long = 5L) {
		val future = CompletableFuture<JsonObject>()
		private val timeoutTimer = timer.schedule(timeout * 1000) {
			if (pendingResponses.containsKey(commandId)) {
				pendingResponses.remove(commandId)
				future.completeExceptionally(Exception("Timeout after $timeout seconds"))
			}
		}

		fun receive(payload: JsonObject) {
			timeoutTimer.cancel()
			future.complete(payload)
		}

		fun receiveError(payload: JsonObject) {
			timeoutTimer.cancel()
			future.completeExceptionally(Exception("$payload"))
		}
	}

	inner class WebCampaignReward(val index: Int) {
		val type = when (index) {
			environment.getAsJsonObject("settings").getAsJsonObject("rewards").getInt("rewardIndex") - 1 -> RewardType.REGISTRATION
			environment.getAsJsonObject("settings").getAsJsonObject("rewards").getInt("milestoneIndex") - 1 -> RewardType.MILESTONE
			else -> RewardType.DAY
		}
		val name get() = localization.getString("Reward.${index + 1}.name", "Unknown")
		val smallIcon get() = "https://$domainName.fortnite.com/images/rewards/prize.${index + 1}@2x.png"
		val largeIcon get() = "https://$domainName.fortnite.com/images/rewards/prize.${index + 1}@3x.png"
	}

	enum class RewardType {
		REGISTRATION, DAY, MILESTONE
	}

	val rewards by lazy {
		val result = mutableListOf<WebCampaignReward>()
		var index = 0
		while (localization.has("Reward.${index + 1}.name")) {
			result.add(WebCampaignReward(index))
			index++
		}
		result
	}
}

fun main() {
	val file = File("webcampaign.json")
	val okHttpClient = OkHttpClient()
	val webCampaign = WebCampaign(okHttpClient, "zerobuildtrials")
	webCampaign.onUpdateProperties = { properties ->
		FileWriter(file).use { EpicApi.GSON.toJson(properties, it) }
	}
	webCampaign.onRequestAuth = { payload ->
		print("Enter authorization code: ")
		val authenticationToken = readLine()!!
		payload.addProperty("authenticationToken", authenticationToken)
		payload.addProperty("redirectUri", "https://${webCampaign.domainName}.fortnite.com/?code=$authenticationToken")
	}
	if (file.exists()) {
		webCampaign.properties = FileReader(file).use { JsonParser.parseReader(it) }.asJsonObject
	}
	webCampaign.connect()
	while (true) {
		runCatching { webCampaign.send(readLine()!!).await() }
	}
}