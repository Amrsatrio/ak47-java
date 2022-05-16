package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.L10N
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.EExternalAuthType
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.friends.FriendV2
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.entities.Message.MentionType
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse

class UserArgument(val max: Int, val greedy: Boolean) : ArgumentType<UserArgument.Result> {
	companion object {
		val EXAMPLES = arrayListOf(
			"noobmaster69",
			"noobmaster69;I take wall;ramirez@gmail.com;d43dce39c5044b8f84bf24f2493fd393"
		)

		@JvmStatic
		fun users(max: Int = Integer.MAX_VALUE, greedy: Boolean = true) = UserArgument(max, greedy)

		@JvmStatic
		fun getUsers(context: CommandContext<CommandSourceStack>, name: String, friends: Array<FriendV2>? = null, loadingText: String? = "Resolving users") =
			context.getArgument(name, Result::class.java).getUsers(context.source, loadingText, friends)
	}

	val separator = ';'

	override fun parse(reader: StringReader): Result {
		val queries = mutableListOf<Query>()
		val terminators = if (greedy) hashSetOf(separator) else hashSetOf(' ', separator)
		var hasNext = reader.canRead() && reader.peek() != ' '
		while (hasNext) {
			readQuery(reader, terminators)?.let(queries::add)
			reader.checkConsumed(terminators)
			hasNext = if (reader.canRead() && reader.peek() == separator) {
				reader.skip()
				true
			} else {
				false
			}
		}
		return Result(queries, max)
	}

	private fun readQuery(reader: StringReader, terminators: Set<Char>): Query? {
		// Explicit friend number
		val isHashtag = reader.peek() == '#'
		if (isHashtag) {
			reader.skip()
			return FriendEntryQuery(reader.readInt(), reader)
		}

		val s = reader.readString0(terminators)
		if (s.isEmpty()) {
			return null
		}

		// Friend number
		if (s.length <= 3) {
			s.toIntOrNull()?.let {
				return FriendEntryQuery(it, reader)
			}
		}

		// Account ID
		if (s.length == 32) {
			return AccountIdQuery(s, reader)
		}

		// Email
		if (Utils.EMAIL_ADDRESS.matcher(s).matches()) {
			return EmailQuery(s, reader)
		}

		// User's saved accounts
		if (BotConfig.get().userArgumentAllowMentions) {
			s.toLongOrNull()?.let {
				return DiscordUserIdQuery(it, reader)
			}
			val matcher = MentionType.USER.pattern.matcher(s)
			if (matcher.find()) {
				matcher.group(1).toLongOrNull()?.let {
					return DiscordUserIdQuery(it, reader)
				}
			}
		}

		// Display name
		return NameQuery(s, reader)
	}

	override fun getExamples() = EXAMPLES

	abstract class Query(val reader: StringReader) {
		val start = reader.cursor
		abstract fun resolve(result: Result)
	}

	class AccountIdQuery(val accountId: String, reader: StringReader) : Query(reader) {
		override fun resolve(result: Result) {
			result.add(null, accountId) // Will be looked up at once later
		}
	}

	class EmailQuery(val email: String, reader: StringReader) : Query(reader) {
		override fun resolve(result: Result) {
			val source = result.source
			val user = source.api.accountService.getByEmail(email).exec().body()!!
			source.userCache[user.id] = user
			result.add(user)
		}
	}

	class NameQuery(val name: String, reader: StringReader) : Query(reader) {
		override fun resolve(result: Result) {
			val source = result.source
			val colonIndex = name.indexOf(':')
			var user: GameProfile? = null
			var authType = EExternalAuthType.epic
			var nameQuery = name
			var errorMessage: String? = null

			if (colonIndex != -1) {
				val authTypeStr = name.substring(0, colonIndex).toLowerCase()
				if (authTypeStr != "epic" && authTypeStr != "psn" && authTypeStr != "xbl") {
					result.error("Unknown account type $authTypeStr. Must be either Epic, PSN, or XBL.")
					return
				}
				authType = EExternalAuthType.valueOf(authTypeStr)
				nameQuery = name.substring(colonIndex + 1)
			}

			if (authType == EExternalAuthType.epic) { // Display name search
				try {
					user = source.api.accountService.getByDisplayName(nameQuery).exec().body()!!
				} catch (e: HttpException) {
					if (e.epicError.errorCode == "errors.com.epicgames.account.account_not_found") {
						errorMessage = "Couldn't find an Epic account with name `$nameQuery`."
					} else throw e
				}
			} else { // External display name search
				user = source.api.accountService.getExternalIdMappingsByDisplayName(authType, nameQuery, true).exec().body()!!.firstOrNull()
				if (user == null) {
					errorMessage = "Couldn't find an Epic account with ${L10N.format("account.ext.${authType.name}.name")} `$nameQuery`."
				}
			}

			if (user != null) {
				source.userCache[user.id] = user
				result.add(user)
				return
			}

			// Show error and search for users with similar names
			val sb = StringBuilder(errorMessage)
			val searchResults = source.api.userSearchService.queryUsers(source.api.currentLoggedIn.id, name, authType).exec().body()!!
			if (searchResults.isNotEmpty()) {
				sb.append("\nDid you mean:")
				val resultMax = if (result.max > 1) 20 else 3
				for ((i, entry) in searchResults.withIndex()) {
					if (i >= resultMax) {
						break
					}
					sb.append("\n\u2022 ")
					entry.matches.firstOrNull()?.let {
						if (it.platform != EExternalAuthType.epic) {
							sb.append(it.platform.name).append(':')
						}
						sb.append(it.value).append(" - ")
					}
					sb.append('`').append(entry.accountId).append('`')
					if (entry.epicMutuals > 0) {
						sb.append(" (").append(Formatters.num.format(entry.epicMutuals)).append(" mutual)")
					}
				}
			}
			result.error(sb.toString())
		}
	}

	class FriendEntryQuery(val index: Int, reader: StringReader) : Query(reader) {
		override fun resolve(result: Result) {
			val source = result.source
			val unsortedFriends = result.friends ?: source.api.friendsService.queryFriends(source.api.currentLoggedIn.id, true).exec().body()!!
			source.queryUsers_map(unsortedFriends.map { it.accountId })
			val sortedFriends = unsortedFriends.sortedFriends(source)
			if (sortedFriends.isEmpty()) {
				result.error("No friends to choose from.")
				return
			}
			val friend = sortedFriends.safeGetOneIndexed(index, reader, start)
			result.add(source.userCache[friend.accountId]!!)
		}
	}

	class DiscordUserIdQuery(val id: Long, reader: StringReader) : Query(reader) {
		override fun resolve(result: Result) {
			val source = result.source
			val discordUser = try {
				source.jda.retrieveUserById(id).complete()
			} catch (e: ErrorResponseException) {
				if (e.errorResponse == ErrorResponse.UNKNOWN_USER) {
					result.error("Unknown Discord user ID: $id")
					return
				} else throw e
			}
			val devices = source.client.savedLoginsManager.getAll(discordUser.id)
			if (devices.isEmpty()) {
				result.error("${discordUser.name} has no saved accounts.")
				return
			}
			devices.forEach { result.add(null, it.accountId) }
		}
	}

	class Result(private val queries: List<Query>, val max: Int) {
		lateinit var source: CommandSourceStack
			private set
		var friends: Array<FriendV2>? = null
			private set

		private val users = mutableMapOf<String, GameProfile?>()
		private val errors = mutableListOf<String>()
		private var limitErrorAdded = false
		private var duplicateErrorIds = hashSetOf<String>()

		@Suppress("UNCHECKED_CAST")
		fun getUsers(source: CommandSourceStack, loadingText: String? = "Resolving users", friends: Array<FriendV2>? = null): Map<String, GameProfile> {
			this.source = source
			this.friends = friends
			if (queries.size > max) {
				throw SimpleCommandExceptionType(LiteralMessage("No more than ${Formatters.num.format(max)} users")).create()
			}
			source.conditionalUseInternalSession()
			loadingText?.let(source::loading)
			queries.forEach { it.resolve(this) }

			val idsToQuery = users.filter { it.value == null }.keys
			if (idsToQuery.isNotEmpty()) {
				source.queryUsers_map(idsToQuery)
				val notFound = mutableListOf<String>()
				for (id in idsToQuery) {
					val user = source.userCache[id]
					if (user != null) {
						users[id] = user
					} else {
						notFound.add(id)
						users.remove(id)
					}
				}
				if (notFound.isNotEmpty()) {
					error("Invalid account ID(s): " + notFound.joinToString())
				}
			}

			if (errors.isNotEmpty()) {
				if (users.isEmpty()) {
					val message = if (errors.size == 1) errors[0] else {
						errors.joinToString("\n", "No users found.\n", limit = 15, transform = ::errorLine)
					}
					throw SimpleCommandExceptionType(LiteralMessage(message)).create()
				}
				source.channel.sendMessage(errors.joinToString("\n", "Epic account lookup errors:\n", limit = 15, transform = ::errorLine)).queue()
			}

			return users as Map<String, GameProfile>
		}

		private fun errorLine(message: String) = message.lines().mapIndexed { i, line -> if (i == 0) "\u2022 $line" else "\u2800$line" }.joinToString("\n")

		fun add(user: GameProfile?, id: String = user!!.id) {
			if (users.size >= max) {
				if (!limitErrorAdded) {
					limitErrorAdded = true
					error("No more than ${Formatters.num.format(max)} users")
				}
				return
			}
			if (id in users) {
				if (id !in duplicateErrorIds) {
					duplicateErrorIds.add(id)
					error("Duplicate: ${source.userCache[id]?.run { "$displayName - `$id`" } ?: "`$id`"}")
				}
				return
			}
			users[id] = user
		}

		fun error(message: String) {
			errors.add(message)
		}
	}
}