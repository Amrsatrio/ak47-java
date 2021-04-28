package com.tb24.discordbot.managers

import com.google.gson.JsonParser
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.util.exec
import com.tb24.fn.EpicApi
import java.io.IOException
import java.util.*

class ChannelsManager(private val client: EpicApi) {
	companion object {
		@JvmStatic val KEY_AVATAR = "avatar"
		@JvmStatic val KEY_AVATAR_BACKGROUND = "avatarBackground"
		@JvmStatic val KEY_APP_INSTALLED = "appInstalled"
		@JvmStatic val COLOR_SCHEMES = arrayOf(
			AvatarColor(0x8EFDE5, 0x1CBA9E, 0x034D3F, "Teal"),
			AvatarColor(0xFF81AE, 0xD8033C, 0x790625, "Party Pink"),
			AvatarColor(0xFFDF00, 0xFBA000, 0x975B04, "Sunburst Yellow"),
			AvatarColor(0xCCF95A, 0x30C11B, 0x194D12, "Dark Green"), // not in the src
			AvatarColor(0xB4F2FE, 0x00ACF2, 0x005679, "Sky Blue"),
			AvatarColor(0x1CA2E6, 0x0C5498, 0x081E3E, "Ocean Blue"),
			AvatarColor(0xFFB4B4, 0xDC718F, 0x7D3449, "Blush"),
			AvatarColor(0xF16712, 0xD8033C, 0x6E0404, "Racing Car Red"),
			AvatarColor(0xAEC1D3, 0x687B8E, 0x36404A, "Titanium"),
			AvatarColor(0xFFAF5D, 0xFF6D32, 0x852A05, "Tangerine"),
			AvatarColor(0xE93FEB, 0x7B009C, 0x500066, "Electric Violet"),
			AvatarColor(0xDFFF73, 0x86CF13, 0x404B07, "Light Green"), // not in the src
			AvatarColor(0xB35EEF, 0x4D1397, 0x2E0A5D, "Storm Purple"),

			// these colors below were not available as an option
			/*AvatarColor(0xD6E0B5, 0x8BA022, 0x404B07, "Army Green"),
			AvatarColor(0xB5F277, 0x339A24, 0x194D12, "Acid Green"),
			AvatarColor(0xD47D49, 0x78371D, 0x4E2312, "Bronze"),
			AvatarColor(0xFFCF7D, 0xA07D40, 0x684B19, "Gold")*/
		)
	}

	class AvatarColor {
		val light: Int
		val dark: Int
		val shade: Int
		val name: String?

		constructor(light: Int, dark: Int, shade: Int, name: String?) {
			this.light = light
			this.dark = dark
			this.shade = shade
			this.name = name
		}

		constructor(jsonArray: String) {
			val list = JsonParser.parseString(jsonArray).asJsonArray
			light = Integer.parseInt(list[0].asString.substring(2), 16)
			dark = Integer.parseInt(list[1].asString.substring(2), 16)
			shade = Integer.parseInt(list[2].asString.substring(2), 16)
			name = null
		}

		override fun toString() = "[\"0x%06X\", \"0x%06X\", \"0x%06X\"]".format(light, dark, shade)
	}

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

		for (userSetting in client.channelsService.queryMultiUserMultiSetting_field(Collections.singletonList(accountId), keysToFetch).exec().body()!!) {
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

	/**
	 * @return previous value if present
	 */
	fun put(accountId: String, settingKey: String, newSetting: String) =
		avatars.getOrPut(accountId) { mutableMapOf() }.put(settingKey, newSetting)
}