package com.tb24.discordbot.commands

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.internal.bind.util.ISO8601Utils
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.StringArgument2.Companion.string2
import com.tb24.discordbot.util.AttachmentUpload
import com.tb24.discordbot.util.awaitOneComponent
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.to
import com.tb24.fn.EpicApi
import com.tb24.fn.model.links.LinkData
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.getInt
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import java.util.*
import java.util.regex.Pattern

class CreativeCommand : BrigadierCommand("creative", "Manages your creative islands and codes.") {
	companion object {
		val MNEMONIC_PATTERN = Pattern.compile("(\\d{4}-\\d{4}-\\d{4})(\\?v=\\d+)?")
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("island code", string2())
			.executes { island(it.source, getString(it, "island code")) }
			.then(literal("raw")
				.executes { island(it.source, getString(it, "island code"), true) }
			)
		)

	private fun island(source: CommandSourceStack, inMnemonic: String, raw: Boolean = false): Int {
		val matcher = MNEMONIC_PATTERN.matcher(inMnemonic)
		if (!matcher.matches()) {
			throw SimpleCommandExceptionType(LiteralMessage("Invalid island code. Must be in this format: `0000-0000-0000`")).create()
		}
		val mnemonic = matcher.group(1)
		val version = matcher.group(2)?.substringAfterLast('=')?.toInt()
		source.conditionalUseInternalSession()
		source.ensureSession()
		source.loading("Searching island code")
		val linkDataJson = source.api.okHttpClient.newCall(source.api.linksService.queryLinkByMnemonic("fn", mnemonic, null, version).request()).exec().to<JsonObject>()
		if (raw) {
			val prettyPrintGson = GsonBuilder().setPrettyPrinting().create()
			source.complete(AttachmentUpload(prettyPrintGson.toJson(linkDataJson).toByteArray(), "Link-%s-%d.json".format(mnemonic.replace("-", ""), linkDataJson.getInt("version"))))
			return Command.SINGLE_SUCCESS
		}
		val linkData = EpicApi.GSON.fromJson(linkDataJson, LinkData::class.java)
		val embed = EmbedBuilder().setColor(COLOR_INFO)
			.populateCreativeLink(linkData)
		if (source.session == source.client.internalSession) {
			source.complete(null, embed.build())
			return Command.SINGLE_SUCCESS
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
				Button.of(ButtonStyle.SUCCESS, "favorite", "Favorited", favoritedEmote)
			} else {
				Button.of(ButtonStyle.SECONDARY, "favorite", "Favorite", favoriteEmote)
			}))
			source.loadingMsg = message
			val choice = message.awaitOneComponent(source, false).componentId
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
		return Command.SINGLE_SUCCESS
	}
}

fun EmbedBuilder.populateCreativeLink(linkData: LinkData): EmbedBuilder {
	this.setTitle(linkData.metadata.title, "https://fortnite.com/creative/island-codes/${linkData.mnemonic}")
		.setDescription(linkData.metadata.tagline.orEmpty() + "\n*by " + linkData.creatorName + '*')
		.addField("Creator", linkData.creatorName, false)
		.setImage(linkData.metadata.image_url ?: linkData.metadata.generated_image_urls?.url)
		.setFooter(linkData.mnemonic)
		.setTimestamp(linkData.published?.toInstant())
	val introduction = linkData.metadata.introduction
	if (!introduction.isNullOrEmpty()) {
		addField("Introduction", introduction, false)
	}
	val descriptionTags = linkData.descriptionTags ?: emptyArray()
	if (descriptionTags.isNotEmpty()) {
		addField("Tags", descriptionTags.joinToString(), false)
	}
	addField("Version", Formatters.num.format(linkData.version), false)
	return this
}