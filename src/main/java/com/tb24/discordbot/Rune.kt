@file:Suppress("NOTHING_TO_INLINE")

package com.tb24.discordbot

import com.tb24.discordbot.commands.CommandSourceStack

object Rune {
	inline fun isBotDev(source: CommandSourceStack) = source.author.idLong in BotConfig.get().adminUserIds
	inline fun hasPremium(source: CommandSourceStack) = source.hasPremium()
}