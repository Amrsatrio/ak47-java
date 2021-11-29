package com.tb24.discordbot.util

import net.dv8tion.jda.api.entities.MessageEmbed

class EmbedMessage(val embed: MessageEmbed) : com.mojang.brigadier.Message {
	override fun getString() = "<embed>"
}