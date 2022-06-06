package com.tb24.discordbot

// Make sure to change these every season
const val SEASON_NUM = 21
const val RESET_HOUR_UTC = 13
val INTRO_NAME: String? = null
val COLLECTIBLE_SCHEDULES = arrayOf(
	CollectibleScheduleData("Season21_Schedule_CustomizableCharacter", "Tover Tokens", " in "),
)
const val STYLE_CURRENCY_SHORT_NAME = "tokens"

data class CollectibleScheduleData(val name: String, val displayName: String, val substringAfter: String)