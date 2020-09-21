package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.safeGetOneIndexed
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.friends.FriendV2
import com.tb24.fn.util.Formatters

class UserArgument(val max: Int) : ArgumentType<UserArgument.Result> {
	companion object {
		val EXAMPLES = arrayListOf(
			"noobmaster69",
			"noobmaster69;I take wall;ramirez@gmail.com;d43dce39c5044b8f84bf24f2493fd393"
		)

		@JvmStatic
		fun users(max: Int = Integer.MAX_VALUE) = UserArgument(max)

		@JvmStatic
		fun getUsers(context: CommandContext<CommandSourceStack>, name: String, friends: List<FriendV2>? = null, loadingText: String = "Resolving users") =
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
		while (canRead() && peek() != separator && peek() != ' ') {
			skip()
		}
		return string.substring(start, cursor)
	}

	override fun parse(reader: StringReader): Result {
		val ids = mutableListOf<Any>()
		var hasNext = reader.canRead() && reader.peek() != ' '
		while (hasNext) {
			if (reader.peek() == '#') { // friend number TODO use this if string length is 3 or less
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
		if (ids.size > max) {
			throw SimpleCommandExceptionType(LiteralMessage("No more than ${Formatters.num.format(max)} recipients")).create()
		}
		return UserResult(ids)
	}

	override fun getExamples() = EXAMPLES

	fun interface Result {
		fun getUsers(source: CommandSourceStack, loadingText: String, friends: List<FriendV2>?): Map<String, GameProfile>
	}

	class FriendEntryQuery(val index: Int, val reader: StringReader, val start: Int = reader.cursor)

	class UserResult(val ids: List<Any>) : Result {
		override fun getUsers(source: CommandSourceStack, loadingText: String, friends: List<FriendV2>?): Map<String, GameProfile> {
			var friends = friends
			source.ensureSession()
			source.loading(loadingText)
			val users = mutableMapOf<String, GameProfile>()
			val idsToQuery = mutableSetOf<String>()

			// verify if recipients are account IDs and transforms non account IDs to account IDs using display name/email lookup
			for (recipient in ids) {
				if (recipient is FriendEntryQuery) {
					val recipientN = recipient.index
					if (friends == null) {
						friends = source.api.friendsService.queryFriends(source.api.currentLoggedIn.id, true).exec().body()!!.sortedBy { it.displayName ?: it.accountId }
					}
					if (friends.isEmpty()) {
						throw SimpleCommandExceptionType(LiteralMessage("No friends to choose from.")).create()
					}
					val friend = friends.safeGetOneIndexed(recipientN, recipient.reader, recipient.start)
					users[friend.accountId] = GameProfile(friend.accountId, friend.displayName)
				} else if (recipient is String) {
					when {
						recipient.isEmpty() -> throw SimpleCommandExceptionType(LiteralMessage("A user cannot be empty.")).create()
						recipient.length != 32 -> {
							val response = (if (Utils.EMAIL_ADDRESS.matcher(recipient).matches()) {
								source.api.accountService.getByEmail(recipient)
							} else {
								source.api.accountService.getByDisplayName(recipient)
							}).exec().body()!!
							users[response.id] = response
						}
						else -> idsToQuery.add(recipient)
					}
				}
			}

			if (idsToQuery.size > 0) {
				source.queryUsers(idsToQuery).forEach { users[it.id] = it }
				val notFound = idsToQuery.filter { !users.contains(it) }
				if (notFound.isNotEmpty()) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid Account ID(s): " + notFound.joinToString())).create()
				}
			}
			val dupes = ids.filterIndexed { index, item -> ids.indexOf(item) != index }
			if (dupes.isNotEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage(dupes.joinToString("\n", "Duplicate users:\n") { users[it]?.run { "$displayName - $it" } ?: it.toString() })).create()
			}
			return users
		}
	}
}