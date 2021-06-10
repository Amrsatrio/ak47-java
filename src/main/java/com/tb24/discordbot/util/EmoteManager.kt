package com.tb24.discordbot.util

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.DiscordBot
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortPersistentResourceItemDefinition
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Emote
import net.dv8tion.jda.api.entities.Icon
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.min

val WHITELIST_ICON_EMOJI_ITEM_TYPES = arrayOf("AccountResource", "ConsumableAccountItem", "Currency", "Gadget", "Stat")
val EMOJI_GUILDS = arrayOf(
	845586443106517012L, // tee 1
	845586502922010635L, // tee 2
	805121146214940682L, // add ur emoji idc 2
	677515124373979155L, // Epic Server Version Status
	Utils.HOMEBASE_GUILD_ID, // AK Facility
	612383214962606081L, // AS Development
	784128953387974736L, // add ur emoji idc
)

fun getItemIconEmoji(item: FortItemStack, bypassWhitelist: Boolean = false): Emote? {
	val client = DiscordBot.instance.discord
	val type = item.primaryAssetType
	val name = item.primaryAssetName
	if (name == "mtxcomplimentary" || name == "mtxgiveaway" || name == "mtxpurchasebonus" || name == "mtxpurchased") {
		return client.getEmoteById(Utils.MTX_EMOJI_ID)
	}
	if (!bypassWhitelist && !(type.isNotEmpty() && type in WHITELIST_ICON_EMOJI_ITEM_TYPES || item.defData is FortPersistentResourceItemDefinition)) {
		return null
	}
	return textureEmote((item.getPreviewImagePath(true) ?: item.getPreviewImagePath()).toString())
}

@Synchronized
fun textureEmote(texturePath: String?): Emote? {
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
	return loadObject<UTexture2D>(texturePath)?.toBufferedImage()?.let { createEmote(name, it) }
}

fun getEmoteByName(name: String): Emote? {
	val client = DiscordBot.instance.discord
	var existing: Emote? = null
	for (guildId in EMOJI_GUILDS) {
		val guild = client.getGuildById(guildId) ?: continue
		existing = guild.getEmotesByName(name, true).firstOrNull()
		if (existing != null) {
			break
		}
	}
	return existing
}

private fun createEmote(name: String, icon: BufferedImage): Emote? {
	val client = DiscordBot.instance.discord
	for (guildId in EMOJI_GUILDS) {
		val guild = client.getGuildById(guildId)
		if (guild == null || guild.emotes.size >= 50) { // server boosts can expire, hardcode it to 50 which is the regular limit
			continue
		}
		if (!guild.selfMember.hasPermission(Permission.MANAGE_EMOTES)) {
			DiscordBot.LOGGER.warn("Insufficient permissions to add emoji :{}: into {}", name, guild)
			continue
		}
		val baos = ByteArrayOutputStream()
		ImageIO.write(icon, "png", baos)
		return guild.createEmote(name, Icon.from(baos.toByteArray(), Icon.IconType.PNG)).complete()
	}
	throw SimpleCommandExceptionType(LiteralMessage("Failed to find a server with free emoji slots.")).create()
}