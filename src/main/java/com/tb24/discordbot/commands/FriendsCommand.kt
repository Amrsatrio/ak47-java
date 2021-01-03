package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.getUsers
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.users
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.friends.FriendV2
import okhttp3.MediaType
import okhttp3.RequestBody
import java.net.URL
import java.util.concurrent.CompletableFuture

class FriendsCommand : BrigadierCommand("friends", "friends operations") {
	val list = literal("list")
		.executes { list(it.source, "friends") }
		.then(literal("friends").executes { list(it.source, "friends") })
		.then(literal("incoming").executes { list(it.source, "incoming") })
		.then(literal("outgoing").executes { list(it.source, "outgoing") })
		.then(literal("suggested").executes { list(it.source, "outgoing") })
		.then(literal("blocklist").executes { list(it.source, "outgoing") })
		.build()

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes(list.command)
		.then(list)
		.then(literal("avatarids").executes { c ->
			val source = c.source
			source.ensureSession()
			val friends = source.api.friendsService.queryFriends(source.api.currentLoggedIn.id, null).exec().body()!!
			val ids = friends.map { it.accountId }
				.chunked(100)
				.map { source.api.channelsService.QueryMultiUserSingleSetting_Field(it, "avatar").future() }
				.apply { CompletableFuture.allOf(*toTypedArray()).await() }
				.flatMap { it.get().body()!!.toList() }
				.map { it.value }
				.toSortedSet()
				.joinToString("\n")
			source.channel.sendFile(ids.toByteArray(), "avatar_ids_${source.api.currentLoggedIn.displayName}.txt").queue()
			Command.SINGLE_SUCCESS
		})
		.then(argument("user", users(1))
			.executes { c ->
				c.source.ensureSession()
				val friends = c.source.api.friendsService.queryFriends(c.source.api.currentLoggedIn.id, true).exec().body()!!.sortedBy { it.displayName ?: it.accountId }
				entryDetails(c.source, getUsers(c, "user", friends).values.first(), friends)
			}
		)

	private fun list(source: CommandSourceStack, type: String): Int {
		source.ensureSession()
		val entries = source.api.friendsService.queryFriends(source.api.currentLoggedIn.id, true).exec().body()!!
		/*source.message.replyPaginated(entries, 20, source.loadingMsg) { content, page, pageCount ->
			MessageBuilder(EmbedBuilder()
				.setTitle("h")
			).build()
		}*/
		return Command.SINGLE_SUCCESS
	}

	private fun entryDetails(source: CommandSourceStack, user: GameProfile, friends: List<FriendV2>): Int {
		val index = friends.indexOfFirst { it.accountId == user.id }
		if (index == -1) {
			// TODO prompt to add friend
			throw SimpleCommandExceptionType(LiteralMessage("Not friends")).create()
		}
		val friend = friends[index]
		val message = source.complete(null, source.createEmbed()
			.setTitle("You're friends with $user")
			.addField("Friend account ID", friend.accountId, false)
			.setFooter("Friends since")
			.setTimestamp(friend.created.toInstant())
			.build())
		message.addReaction("📛").queue()
		//message.addReaction("📝").queue()
		message.addReaction("❌").queue()
		val choice = message.awaitReactions({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
			max = 1
			time = 30000
			errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		}).await().first().reactionEmote.name
		return when (choice) {
			"📛" -> aliasOrNote(source, friend, false)
			"📝" -> aliasOrNote(source, friend, true)
			"❌" -> remove(source, friend)
			else -> throw SimpleCommandExceptionType(LiteralMessage("Invalid input.")).create()
		}
	}

	private fun aliasOrNote(source: CommandSourceStack, friend: FriendV2, bNote: Boolean): Int {
		val propName = if (bNote) "note" else "nickname"
		val old = friend.alias
		source.complete("The current $propName is: `${old.orUnset()}`\nEnter the new $propName: (⏱ 45s)")
		var new = source.channel.awaitMessages({ collected, _, _ -> collected.author.idLong == source.author.idLong }, AwaitMessagesOptions().apply {
			max = 1
			time = 45000L
			errors = arrayOf(CollectorEndReason.TIME)
		}).await().first().contentRaw
		val friendsService = source.api.friendsService
		val accountId = source.api.currentLoggedIn.id
		val friendId = friend.accountId
		if (new == "clear") {
			new = ""
			(if (bNote) friendsService.deleteFriendNote(accountId, friendId) else friendsService.deleteFriendAlias(accountId, friendId)).exec()
		} else {
			val body = RequestBody.create(MediaType.get("text/plain"), new)
			(if (bNote) friendsService.setFriendNote(accountId, friendId, body) else friendsService.setFriendAlias(accountId, friendId, body)).exec()
		}
		source.complete(null, source.createEmbed()
			.setTitle("✅ Updated $propName of ${friend.displayName}")
			.setDescription("${old.orUnset()} \u2192 ${new.orUnset()}")
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun remove(source: CommandSourceStack, friend: FriendV2): Int {
		return Command.SINGLE_SUCCESS
	}

	private fun String?.orUnset() = this?.takeIf { it.isNotEmpty() } ?: "(unset)"
}

fun main() {
	URL("H").readBytes()
}