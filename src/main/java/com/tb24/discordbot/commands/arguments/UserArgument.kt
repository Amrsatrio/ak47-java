package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.L10N
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.safeGetOneIndexed
import com.tb24.discordbot.util.sortedFriends
import com.tb24.fn.model.account.EExternalAuthType
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.friends.FriendV2
import com.tb24.fn.util.Formatters

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

	private fun StringReader.checkConsumed() {
		if (canRead() && peek() != separator && peek() != ' ') {
			throw SimpleCommandExceptionType(LiteralMessage("Unrecognized argument")).createWithContext(this)
		}
	}

	private fun StringReader.readString0(): String {
		if (!canRead()) {
			return ""
		}
		if (StringReader.isQuotedStringStart(peek())) {
			return readQuotedString()
		}
		val start = cursor
		while (canRead() && peek() != separator && (greedy || peek() != ' ')) {
			skip()
		}
		return string.substring(start, cursor)
	}

	override fun parse(reader: StringReader): Result {
		val ids = mutableListOf<Any>()
		var hasNext = reader.canRead() && reader.peek() != ' '
		while (hasNext) {
			val isHashtag = reader.peek() == '#'
			if (isHashtag) { // friend number TODO use this if string length is 3 or less
				reader.skip()
				ids.add(FriendEntryQuery(reader.readInt(), reader))
			} else { // display name, email, or account id
				ids.add(reader.readString0())
			}
			reader.checkConsumed()
			hasNext = if (reader.canRead() && reader.peek() == separator) {
				reader.skip()
				true
			} else {
				false
			}
		}
		return Result(ids, max)
	}

	override fun getExamples() = EXAMPLES

	class FriendEntryQuery(val index: Int, val reader: StringReader, val start: Int = reader.cursor)

	class Result(val ids: List<Any>, val max: Int) {
		@Suppress("UNCHECKED_CAST")
		fun getUsers(source: CommandSourceStack, loadingText: String? = null, friends: Array<FriendV2>? = null): Map<String, GameProfile> {
			var max = max
			if (max == -1) {
				max = source.getSavedAccountsLimit()
			}
			if (ids.size > max) {
				throw SimpleCommandExceptionType(LiteralMessage("No more than ${Formatters.num.format(max)} users")).create()
			}
			source.conditionalUseInternalSession()
			loadingText?.let(source::loading)
			val users = Array<GameProfile?>(ids.size) { null } as Array<GameProfile>
			val idsToQuery = mutableSetOf<String>()
			val idIndices = mutableListOf<Int>()

			// verify if recipients are account IDs and transforms non account IDs to account IDs using display name/email lookup
			for ((i, query) in ids.withIndex()) {
				if (query is FriendEntryQuery) {
					val recipientN = query.index
					val unsortedFriends = friends ?: source.api.friendsService.queryFriends(source.api.currentLoggedIn.id, true).exec().body()!!
					source.queryUsers_map(unsortedFriends.map { it.accountId })
					val sortedFriends = unsortedFriends.sortedFriends(source)
					if (sortedFriends.isEmpty()) {
						throw SimpleCommandExceptionType(LiteralMessage("No friends to choose from.")).create()
					}
					val friend = sortedFriends.safeGetOneIndexed(recipientN, query.reader, query.start)
					users[i] = source.userCache[friend.accountId]!!
				} else if (query is String) {
					when {
						query.isEmpty() -> throw SimpleCommandExceptionType(LiteralMessage("A user cannot be empty.")).create()
						query.length == 32 -> {
							idsToQuery.add(query)
							idIndices.add(i)
						}
						else -> {
							val user = (if (Utils.EMAIL_ADDRESS.matcher(query).matches()) {
								source.api.accountService.getByEmail(query).exec().body()!!
							} else {
								val colonIndex = query.indexOf(':')
								var result: GameProfile? = null
								var externalAuthType = EExternalAuthType.epic
								var errorMessage: String? = null
								if (colonIndex == -1) {
									try {
										result = source.api.accountService.getByDisplayName(query).exec().body()!!
									} catch (e: HttpException) {
										if (e.epicError.errorCode == "errors.com.epicgames.account.account_not_found") {
											errorMessage = "Couldn't find an Epic account with name `$query`."
										} else {
											throw e
										}
									}
								} else {
									val externalAuthTypeStr = query.substring(0, colonIndex).toLowerCase()
									if (externalAuthTypeStr != "psn" && externalAuthTypeStr != "xbl") {
										throw SimpleCommandExceptionType(LiteralMessage("Must be either PSN or XBL.")).create()
									}
									externalAuthType = EExternalAuthType.valueOf(externalAuthTypeStr)
									val externalNameQuery = query.substring(colonIndex + 1)
									result = source.api.accountService.getExternalIdMappingsByDisplayName(externalAuthType, externalNameQuery, true).exec().body()!!.firstOrNull()
									if (result == null) {
										errorMessage = "Couldn't find an Epic account with ${L10N.format("account.ext.${externalAuthType.name}.name")} `$externalNameQuery`."
									}
								}
								if (result != null) {
									source.userCache[result.id] = result
								} else {
									val sb = StringBuilder(errorMessage)
									val searchResults = source.api.userSearchService.queryUsers(source.api.currentLoggedIn.id, query, externalAuthType).exec().body()!!
									if (searchResults.isNotEmpty()) {
										sb.append("\nDid you mean:")
										for ((i, entry) in searchResults.withIndex()) {
											sb.append("\n\u2022 ")
											if (i >= 20) {
												sb.append("... and more ...")
												break
											}
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
									throw SimpleCommandExceptionType(LiteralMessage(sb.toString())).create()
								}
								result
							})
							users[i] = user
						}
					}
				}
			}

			if (idsToQuery.size > 0) {
				source.queryUsers_map(idsToQuery)
				val notFound = mutableListOf<String>()
				for ((i, id) in idsToQuery.withIndex()) {
					val user = source.userCache[id]
					if (user != null) {
						users[idIndices[i]] = user
					} else {
						notFound.add(id)
					}
				}
				if (notFound.isNotEmpty()) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid Account ID(s): " + notFound.joinToString())).create()
				}
			}
			val dupes = ids.filterIndexed { index, item -> ids.indexOf(item) != index }
			if (dupes.isNotEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage(dupes.joinToString("\n", "Duplicate users:\n") { source.userCache[it]?.run { "$displayName - $it" } ?: it.toString() })).create()
			}
			return users.associateBy { it.id }
		}
	}
}