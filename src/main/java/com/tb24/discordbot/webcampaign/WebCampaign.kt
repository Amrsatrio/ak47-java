package com.tb24.discordbot.webcampaign

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tb24.discordbot.util.await
import com.tb24.fn.EpicApi
import com.tb24.fn.util.getBoolean
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

class WebCampaign(val okHttpClient: OkHttpClient) {
	val LOGGER = LoggerFactory.getLogger(WebCampaign::class.java)
	lateinit var ws: WebSocket
	var connectionId: String? = null
	var sequence = 0
	val pendingResponses = ConcurrentHashMap<String, PendingResponse>()
	val states = ConcurrentHashMap<String, JsonObject>() // type:key -> state
	val timer = Timer()
	var properties = JsonObject()
	var onUpdateProperties: ((JsonObject) -> Unit)? = null
	var onRequestAuth: ((JsonObject) -> Unit)? = null
	//var onStateUpdated

	fun send(type: String, payload: JsonObject? = null): CompletableFuture<JsonObject> {
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
		println("Send: $data")
		ws.send(data.toString())
		val pendingResponse = PendingResponse(commandId)
		pendingResponses[commandId] = pendingResponse
		return pendingResponse.future
	}

	fun receive(data: JsonObject) {
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

	fun connect() {
		ws = okHttpClient.newWebSocket(Request.Builder().url("wss://backend.epic-fortnite-kawaii.playtotv.com/handler").build(), object : WebSocketListener() {
			override fun onMessage(webSocket: WebSocket, text: String) {
				println("Recv: $text")
				receive(JsonParser.parseString(text).asJsonObject)
			}
		})
		sendHello()
		val pingInterval = 10L * 1000L
		timer.scheduleAtFixedRate(pingInterval, pingInterval) {
			send("service.ping")
		}
	}

	private fun sendHello() {
		send("service.hello", JsonObject().apply { add("properties", properties) }).await()
		if (!properties.has("epicSessionToken")) {
			val myOnRequestAuth = onRequestAuth
			if (myOnRequestAuth != null) {
				val payload = JsonObject()
				myOnRequestAuth(payload)
				send("account.authenticate", payload).await()
			} else {
				LOGGER.warn("No onRequestAuth callback set, will not try to authenticate")
			}
		}
	}

	inner class PendingResponse(commandId: String, timeout: Long = 5L) {
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
}

fun main() {
	val file = File("webcampaign.json")
	val okHttpClient = OkHttpClient()
	val webCampaign = WebCampaign(okHttpClient)
	webCampaign.onUpdateProperties = { properties ->
		FileWriter(file).use { EpicApi.GSON.toJson(properties, it) }
	}
	webCampaign.onRequestAuth = { payload ->
		print("Enter authorization code: ")
		val authenticationToken = readLine()!!
		payload.addProperty("authenticationToken", authenticationToken)
		payload.addProperty("redirectUri", "https://thenindo.fortnite.com/?code=$authenticationToken")
	}
	if (file.exists()) {
		webCampaign.properties = FileReader(file).use { JsonParser.parseReader(it) }.asJsonObject
	}
	webCampaign.connect()
	webCampaign.send("participant.updateUserObject")
}