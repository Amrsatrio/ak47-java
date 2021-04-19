package com.tb24.discordbot.commands

import com.google.gson.internal.bind.util.ISO8601Utils
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.AwaitReactionsOptions
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.awaitReactions
import com.tb24.discordbot.util.exec
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import java.util.*
import java.util.regex.Pattern

class CreativeCommand : BrigadierCommand("creative", "Manages your creative islands and codes.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("island code", greedyString())
			.executes { c ->
				val source = c.source
				val mnemonic = getString(c, "island code")
				if (!Pattern.matches("\\d{4}-\\d{4}-\\d{4}", mnemonic)) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid island code. Must be in this format: `0000-0000-0000`")).create()
				}
				if (source.api.userToken == null) {
					source.session = source.client.internalSession
				}
				source.ensureSession()
				source.loading("Searching island code")
				val linkData = source.api.linksService.QueryLinkByMnemonic("fn", mnemonic, "Creative:Island", null).exec().body()!!
				val embed = EmbedBuilder().setColor(COLOR_INFO)
					.setAuthor(linkData.creatorName + " presents")
					.setTitle(linkData.metadata.title, "https://fortnite.com/creative/island-codes/$mnemonic")
					.setFooter(mnemonic)
					.setTimestamp(linkData.published?.toInstant())
				linkData.metadata.tagline?.let { embed.setDescription(it) }
				linkData.metadata.introduction?.let { embed.addField("Introduction", it, false) }
				linkData.descriptionTags?.let { embed.addField("Tags", it.joinToString(), false) }
				(linkData.metadata.image_url ?: linkData.metadata.generated_image_urls?.url)?.let(embed::setImage)
				val message = source.complete(null, embed.build())
				if (source.session == source.client.internalSession) {
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
				val trigger = if (isFavorite) "💔" else "❤"
				message.addReaction(trigger).queue()
				val choice = message.awaitReactions({ reaction, user, _ -> user == source.author && reaction.reactionEmote.emoji == trigger }, AwaitReactionsOptions().apply { time = 60000L; max = 1 }).await().firstOrNull()
				if (choice != null) {
					val successMessage = if (isFavorite) {
						source.api.fortniteService.removeCodeFromCreativeFavorites(source.api.currentLoggedIn.id, mnemonic).exec()
						"Removed from favorites"
					} else {
						source.api.fortniteService.addCodeToCreativeFavorites(source.api.currentLoggedIn.id, mnemonic).exec()
						"Added to favorites"
					}
					message.editMessage(embed.setFooter("✅ $successMessage \u2022 $mnemonic").build()).complete()
				} else if (message.member!!.hasPermission(Permission.MESSAGE_MANAGE)) {
					message.clearReactions().queue()
				}
				Command.SINGLE_SUCCESS
			}
		)
}