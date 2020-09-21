package com.tb24.discordbot

import com.tb24.discordbot.util.exec
import com.tb24.fn.EpicApi
import java.io.IOException
import java.util.*

class ChannelsManager(private val client: EpicApi) {
	companion object {
		@JvmStatic val KEY_AVATAR = "avatar"
		@JvmStatic val KEY_AVATAR_BACKGROUND = "avatarBackground"
		@JvmStatic val KEY_APP_INSTALLED = "appInstalled"
		@JvmStatic val COLORS = mapOf(
			"Ocean Blue" to arrayOf("#1CA2E6", "#0C5498", "#081E3E"),
			"Sky Blue" to arrayOf("#B4F2FE", "#00ACF2", "#005679"),
			"Acid Green" to arrayOf("#B5F277", "#339A24", "#194D12"),
			"Army Green" to arrayOf("#D6E0B5", "#8BA022", "#404B07"),
			"Storm Purple" to arrayOf("#B35EEF", "#4D1397", "#2E0A5D"),
			"Electric Violet" to arrayOf("#E93FEB", "#7B009C", "#500066"),
			"Party Pink" to arrayOf("#FF81AE", "#D8033C", "#790625"),
			"Racing Car Red" to arrayOf("#F16712", "#D8033C", "#6E0404"),
			"Tangerine" to arrayOf("#FFAF5D", "#FF6D32", "#852A05"),
			"Sunburst Yellow" to arrayOf("#FFDF00", "#FBA000", "#975B04"),
			"Bronze" to arrayOf("#D47D49", "#78371D", "#4E2312"),
			"Blush" to arrayOf("#FFB4B4", "#DC718F", "#7D3449"),
			"Teal" to arrayOf("#8EFDE5", "#1CBA9E", "#034D3F"),
			"Titanium" to arrayOf("#AEC1D3", "#687B8E", "#36404A"),
			"Gold" to arrayOf("#FFCF7D", "#A07D40", "#684B19")
		)
	}

	enum class ColorIndex { LIGHT, DARK, SHADE }

	private val avatars = mutableMapOf<String, MutableMap<String, String>>()

	@Throws(HttpException::class, IOException::class)
	fun getUserSettings(accountId: String, vararg settingKeys: String): Array<String?> {
		val result = arrayOfNulls<String>(settingKeys.size)
		val keysToFetch = mutableListOf<String>()
		var existingMap = avatars[accountId]

		if (existingMap != null) {
			for (i in settingKeys.indices) {
				val settingKey = settingKeys[i]
				val existingValue = existingMap[settingKey]

				if (existingValue != null) {
					result[i] = existingValue
				} else {
					keysToFetch.add(settingKey)
				}
			}
		} else {
			keysToFetch.addAll(settingKeys)
		}

		if (keysToFetch.size == 0) {
			return result
		}

		for (userSetting in client.channelsService.QueryMultiUserMultiSetting_Field(Collections.singletonList(accountId), keysToFetch).exec().body()!!) {
			// we only fetch 1 account id
			if (existingMap == null) {
				existingMap = mutableMapOf()
				avatars[accountId] = existingMap
			}

			existingMap[userSetting.key] = userSetting.value
			result[settingKeys.indexOf(userSetting.key)] = userSetting.value
		}

		return result
	}

	fun clear() {
		avatars.clear()
	}
}