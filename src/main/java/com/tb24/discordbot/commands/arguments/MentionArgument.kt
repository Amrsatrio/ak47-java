package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.commands.CommandSourceStack
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.Message.MentionType
import net.dv8tion.jda.api.utils.MiscUtil.parseSnowflake
import net.dv8tion.jda.internal.JDAImpl
import net.dv8tion.jda.internal.entities.EmoteImpl
import java.util.*
import java.util.regex.Matcher

class MentionArgument private constructor(private val mentionType: MentionType) : ArgumentType<MentionArgument.Resolver> {
	override fun parse(reader: StringReader): Resolver {
		val start = reader.cursor
		while (reader.canRead() && reader.peek() != ' ') {
			reader.skip()
		}
		return Resolver(mentionType, reader.string.substring(start, reader.cursor))
	}

	class Resolver(private val mentionType: MentionType, private val input: String) {
		lateinit var message: Message

		fun resolve(message: Message): Collection<IMentionable> {
			this.message = message
			val id = input.toLongOrNull()
			if (id != null) {
				return when (mentionType) {
					MentionType.USER -> setOf(message.jda.getUserById(id) ?: message.jda.retrieveUserById(id).complete())
					MentionType.ROLE -> (if (message.channelType.isGuild) message.guild.getRoleById(id) else message.jda.getRoleById(id))?.let { setOf(it) }
					MentionType.CHANNEL -> message.jda.getTextChannelById(id)?.let { setOf(it) }
					MentionType.EMOTE -> message.jda.getEmoteById(id)?.let { setOf(it) }
					else -> null
				} ?: emptySet()
			}
			return when (mentionType) {
				MentionType.USER -> processMentions(input, HashSet(), true) { matchUser(it) }
				MentionType.ROLE -> processMentions(input, HashSet(), true) { matchRole(it) }
				MentionType.CHANNEL -> processMentions(input, HashSet(), true) { matchTextChannel(it) }
				MentionType.EMOTE -> processMentions(input, HashSet(), true) { matchEmote(it) }
				else -> emptySet()
			}
		}

		private fun <T, C : MutableCollection<T>> processMentions(input: String, collection: C, distinct: Boolean, map: (Matcher) -> T?): C {
			val matcher = mentionType.pattern.matcher(input)
			while (matcher.find()) {
				try {
					val elem = map(matcher)
					if (elem == null || distinct && collection.contains(elem)) {
						continue
					}
					collection.add(elem)
				} catch (ignored: NumberFormatException) {
				}
			}
			return collection
		}

		private fun matchUser(matcher: Matcher): User? {
			val userId = parseSnowflake(matcher.group(1))
			return message.jda.getUserById(userId) ?: message.jda.retrieveUserById(userId).complete()
		}

		private fun matchRole(matcher: Matcher): Role? {
			val roleId = parseSnowflake(matcher.group(1))
			return if (message.channelType.isGuild) {
				message.guild.getRoleById(roleId)
			} else {
				message.jda.getRoleById(roleId)
			}
		}

		private fun matchTextChannel(matcher: Matcher): TextChannel? {
			val channelId = parseSnowflake(matcher.group(1))
			return message.jda.getTextChannelById(channelId)
		}

		private fun matchEmote(m: Matcher): Emote? {
			val emoteId = parseSnowflake(m.group(2))
			val name = m.group(1)
			val animated = m.group(0).startsWith("<a:")
			return message.jda.getEmoteById(emoteId) ?: EmoteImpl(emoteId, message.jda as JDAImpl).setName(name).setAnimated(animated)
		}
	}

	companion object {
		@JvmStatic
		fun mention(mentionType: MentionType) = MentionArgument(mentionType)

		@JvmStatic
		fun getMention(context: CommandContext<CommandSourceStack>, name: String) =
			context.getArgument(name, Resolver::class.java).resolve(context.source.message)
	}
}