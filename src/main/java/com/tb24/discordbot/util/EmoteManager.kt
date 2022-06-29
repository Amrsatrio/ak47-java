package com.tb24.discordbot.util

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.DiscordBot
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortPersistentResourceItemDefinition
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Icon
import net.dv8tion.jda.api.entities.emoji.Emoji
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.min

val WHITELIST_ICON_EMOJI_ITEM_TYPES = arrayOf("AccountResource", "ConsumableAccountItem", "Currency", "Gadget", "Stat")
val EMOJI_GUILDS by lazy { BotConfig.get().emojiGuildIds + BotConfig.get().homeGuildId }

fun getItemIconEmoji(item: FortItemStack, bypassWhitelist: Boolean = false): Emoji? {
	val client = DiscordBot.instance.discord
	val type = item.primaryAssetType
	val name = item.primaryAssetName
	if (name == "mtxcomplimentary" || name == "mtxgiveaway" || name == "mtxpurchasebonus" || name == "mtxpurchased") {
		return client.getEmojiById(Utils.MTX_EMOJI_ID)
	}
	if (!bypassWhitelist && !(type.isNotEmpty() && type in WHITELIST_ICON_EMOJI_ITEM_TYPES || item.defData is FortPersistentResourceItemDefinition)) {
		return null
	}
	return textureEmote((item.getPreviewImagePath(true) ?: item.getPreviewImagePath()).toString())
}

@Synchronized
fun textureEmote(texturePath: String?): Emoji? {
	if (texturePath == null || texturePath == "None") {
		return null
	}
	var name = texturePath.substringAfterLast('.').replace('-', '_')
	while (name.startsWith("T_", true)) {
		name = name.substring(2)
	}
	if (name.startsWith("Icon_", true)) {
		name = name.substring(5)
	}
	name = name.substring(0, min(32, name.length))
	getEmoteByName(name)?.let { return it }
	return loadObject<UTexture2D>(texturePath)?.toBufferedImage()?.let { createEmoji(name, it) }
}

fun getEmoteByName(name: String): Emoji? {
	val client = DiscordBot.instance.discord
	var existing: Emoji? = null
	for (guildId in EMOJI_GUILDS) {
		val guild = client.getGuildById(guildId)
			?: throw SimpleCommandExceptionType(LiteralMessage("Emoji servers are not fully loaded yet. Please try again in a few minutes.")).create()
		existing = guild.getEmojisByName(name, true).firstOrNull()
		if (existing != null) {
			break
		}
	}
	return existing
}

private fun createEmoji(name: String, icon: BufferedImage): Emoji? {
	val client = DiscordBot.instance.discord
	for (guildId in EMOJI_GUILDS) {
		val guild = client.getGuildById(guildId)
		if (guild == null || guild.emojis.size >= 50) { // server boosts can expire, hardcode it to 50 which is the regular limit
			continue
		}
		if (!guild.selfMember.hasPermission(Permission.MANAGE_EMOJIS_AND_STICKERS)) {
			DiscordBot.LOGGER.warn("Insufficient permissions to add emoji :{}: into {}", name, guild)
			continue
		}
		val baos = ByteArrayOutputStream()
		ImageIO.write(icon, "png", baos)
		return guild.createEmoji(name, Icon.from(baos.toByteArray(), Icon.IconType.PNG)).complete()
	}
	throw SimpleCommandExceptionType(LiteralMessage("Failed to find a server with free emoji slots.")).create()
}