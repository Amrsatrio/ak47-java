package com.tb24.discordbot.commands

import com.google.gson.internal.bind.util.ISO8601Utils
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.awaitOneInteraction
import com.tb24.discordbot.util.exec
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.Button
import net.dv8tion.jda.api.interactions.components.ButtonStyle
import java.util.*
import java.util.regex.Pattern

class CreativeCommand : BrigadierCommand("creative", "Manages your creative islands and codes.") {
	companion object {
		private val MNEMONIC_PATTERN = Pattern.compile("(\\d{4}-\\d{4}-\\d{4})(\\?v=\\d+)?")
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("island code", greedyString())
			.executes { c ->
				val source = c.source
				val inMnemonic = getString(c, "island code")
				val matcher = MNEMONIC_PATTERN.matcher(inMnemonic)
				if (!matcher.matches()) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid island code. Must be in this format: `0000-0000-0000`")).create()
				}
				val mnemonic = matcher.group(1)
				val version = matcher.group(2)?.substringAfterLast('=')?.toInt()
				source.conditionalUseInternalSession()
				source.ensureSession()
				source.loading("Searching island code")
				val linkData = source.api.linksService.queryLinkByMnemonic("fn", mnemonic, null, version).exec().body()!!
				val embed = EmbedBuilder().setColor(COLOR_INFO)
					.setAuthor(linkData.creatorName + " presents")
					.setTitle(linkData.metadata.title, "https://fortnite.com/creative/island-codes/$mnemonic")
					.setDescription(linkData.metadata.tagline)
					.setImage(linkData.metadata.image_url ?: linkData.metadata.generated_image_urls?.url)
					.setFooter(mnemonic)
					.setTimestamp(linkData.published?.toInstant())
				val introduction = linkData.metadata.introduction
				if (!introduction.isNullOrEmpty()) {
					embed.addField("Introduction", introduction, false)
				}
				val descriptionTags = linkData.descriptionTags ?: emptyArray()
				if (descriptionTags.isNotEmpty()) {
					embed.addField("Tags", descriptionTags.joinToString(), false)
				}
				embed.addField("Version", Formatters.num.format(linkData.version), false)
				if (source.session == source.client.internalSession) {
					source.complete(null, embed.build())
					return@executes Command.SINGLE_SUCCESS
				}
				var isFavorite = false
				var oldest: Date? = null
				while (true) {
					val response = source.api.fortniteService.queryCreativeFavorites(source.api.currentLoggedIn.id, 100, oldest?.let { ISO8601Utils.format(it) }).exec().body()!!
					for (entry in response.results) {
						if (entry.linkData.mnemonic == mnemonic) {
							isFavorite = true
							break
						}
						if (oldest == null || entry.sortDate < oldest) {
							oldest = entry.sortDate
						}
					}
					if (isFavorite || !response.hasMore) {
						break
					}
				}
				while (true) {
					val message = source.complete(null, embed.build(), ActionRow.of(if (isFavorite) {
						Button.of(ButtonStyle.SUCCESS, "favorite", "Favorited", Emoji.fromEmote(favoritedEmote!!))
					} else {
						Button.of(ButtonStyle.SECONDARY, "favorite",  "Favorite", Emoji.fromEmote(favoriteEmote!!))
					}))
					source.loadingMsg = message
					val choice = message.awaitOneInteraction(source.author, false).componentId
					if (choice == "favorite") {
						isFavorite = if (isFavorite) {
							source.api.fortniteService.removeCodeFromCreativeFavorites(source.api.currentLoggedIn.id, mnemonic).exec()
							false
						} else {
							source.api.fortniteService.addCodeToCreativeFavorites(source.api.currentLoggedIn.id, mnemonic).exec()
							true
						}
					} else {
						break
					}
				}
				Command.SINGLE_SUCCESS
			}
		)
}