package com.tb24.discordbot

// Make sure to change these every season
const val SEASON_NUM = 21
const val RESET_HOUR_UTC = 13
val INTRO_NAME: String? = null
val COLLECTIBLE_SCHEDULES = arrayOf(
	CollectibleScheduleData("season20_schedule_collectibles_pickaxe", "Omni Chips", " at "),
	CollectibleScheduleData("season20_leveluppack_schedule", "Omega Knight Level Up Tokens", "Collect Level Up Token ")
)
const val STYLE_CURRENCY_SHORT_NAME = "chips"

data class CollectibleScheduleData(val name: String, val displayName: String, val substringAfter: String)