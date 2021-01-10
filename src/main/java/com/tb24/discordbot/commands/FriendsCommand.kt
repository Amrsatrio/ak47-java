package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.L10N
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.getUsers
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.users
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.friends.FriendV2
import com.tb24.fn.model.friends.FriendsSummary
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import okhttp3.MediaType
import okhttp3.RequestBody
import java.util.concurrent.CompletableFuture

class FriendsCommand : BrigadierCommand("friends", "Epic Friends operations.", arrayOf("f")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { list(it.source, "friends") }.then(literal("withid").executes { list(it.source, "friends", true) })
		.then(literal("incoming").executes { list(it.source, "incoming") }.then(literal("withid").executes { list(it.source, "incoming", true) }))
		.then(literal("outgoing").executes { list(it.source, "outgoing") }.then(literal("withid").executes { list(it.source, "outgoing", true) }))
		.then(literal("blocklist").executes { list(it.source, "blocklist") }.then(literal("withid").executes { list(it.source, "blocklist", true) }))
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
				val summary = c.source.api.friendsService.queryFriendsSummary(c.source.api.currentLoggedIn.id, true).exec().body()!!
				val friends = summary.friends.sortedFriends()
				entryDetails(c.source, getUsers(c, "user", friends).values.first(), summary)
			}
		)

	private fun list(source: CommandSourceStack, type: String, withId: Boolean = false): Int {
		source.ensureSession()
		source.loading("Querying friends")
		val call = when (type) {
			"friends" -> source.api.friendsService.queryFriends(source.api.currentLoggedIn.id, true)
			"incoming" -> source.api.friendsService.queryIncomingFriendRequests(source.api.currentLoggedIn.id, true)
			"outgoing" -> source.api.friendsService.queryOutgoingFriendRequests(source.api.currentLoggedIn.id, true)
			//"suggested" -> source.api.friendsService.querySuggestedFriends(source.api.currentLoggedIn.id, true)
			"blocklist" -> source.api.friendsService.queryBlockedPlayers(source.api.currentLoggedIn.id, true)
			else -> throw AssertionError()
		}
		val entries = call.exec().body()!!.sortedFriends()
		source.message.replyPaginated(entries, 30, source.loadingMsg) { content, page, pageCount ->
			val entriesStart = page * 30 + 1
			var entriesEnd = entriesStart
			val chunks = content.chunked(15) { chunk ->
				MessageEmbed.Field("", chunk.joinToString("\n") {
					"%,d. %s%s".format(entriesEnd++, when {
						!it.alias.isNullOrEmpty() -> if (withId) "${it.alias.escapeMarkdown()} (${it.displayName.escapeMarkdown()}) `${it.accountId}`" else "${it.alias.escapeMarkdown()} (${it.displayName.escapeMarkdown()})"
						!it.displayName.isNullOrEmpty() -> if (withId) "${it.displayName.escapeMarkdown()} `${it.accountId}`" else it.displayName.escapeMarkdown()
						else -> "`${it.accountId}`"
					}, if (type != "friends" || it.created == null || System.currentTimeMillis() >= it.created.time + 2L * 24L * 60L * 60L * 1000L) "" else "*")
				}, true)
			}
			val embed = source.createEmbed()
				.setTitle(type)
				.setDescription("Showing %,d to %,d of %,d entries\n* = Not eligible for gifting".format(entriesStart, entriesEnd - 1, entries.size))
				.setFooter("Page %d of %d".format(page + 1, pageCount))
			chunks.forEach(embed::addField)
			MessageBuilder(embed.build()).build()
		}
		return Command.SINGLE_SUCCESS
	}

	private fun entryDetails(source: CommandSourceStack, user: GameProfile, summary: FriendsSummary): Int {
		summary.friends.firstOrNull { it.accountId == user.id }?.let {
			return friends(source, it, user)
		}
		summary.incoming.firstOrNull { it.accountId == user.id }?.let {
			return incoming(source, it, user)
		}
		summary.outgoing.firstOrNull { it.accountId == user.id }?.let {
			return outgoing(source, it, user)
		}
		// suggested here
		summary.blocklist.firstOrNull { it.accountId == user.id }?.let {
			return blocked(source, it, user)
		}
		return promptToSendFriendRequest(source, user)
	}

	private fun friends(source: CommandSourceStack, friend: FriendV2, user: GameProfile): Int {
		val message = source.complete(null, source.createEmbed()
			.setTitle("You're friends with ${friend.displayName?.escapeMarkdown() ?: friend.accountId}")
			.setDescription("📛 Change nickname\n🗑 Remove friend\n🚫 Block")
			.populateFriendInfo(friend)
			.build())
		message.addReaction("📛").queue()
		//message.addReaction("📝").queue()
		message.addReaction("🗑").queue()
		message.addReaction("🚫").queue()
		val choice = message.awaitReactions({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
			max = 1
			time = 30000
			errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		}).await().first().reactionEmote.name
		return when (choice) {
			"📛" -> aliasOrNote(source, friend, false)
			"📝" -> aliasOrNote(source, friend, true)
			"🗑" -> remove(source, user)
			"🚫" -> block(source, user)
			else -> throw SimpleCommandExceptionType(LiteralMessage("Invalid input.")).create()
		}
	}

	private fun aliasOrNote(source: CommandSourceStack, friend: FriendV2, note: Boolean): Int {
		val propName = if (note) "note" else "nickname"
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
			(if (note) friendsService.deleteFriendNote(accountId, friendId) else friendsService.deleteFriendAlias(accountId, friendId)).exec()
		} else {
			val body = RequestBody.create(MediaType.get("text/plain"), new)
			(if (note) friendsService.setFriendNote(accountId, friendId, body) else friendsService.setFriendAlias(accountId, friendId, body)).exec()
		}
		source.complete(null, source.createEmbed()
			.setTitle("✅ Updated $propName of ${friend.displayName}")
			.setDescription("${old.orUnset()} \u2192 ${new.orUnset()}")
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun incoming(source: CommandSourceStack, friend: FriendV2, user: GameProfile): Int {
		val message = source.complete(null, source.createEmbed()
			.setTitle("Friend request from ${friend.displayName?.escapeMarkdown() ?: friend.accountId}")
			.setDescription("✅ Accept\n❌ Reject\n🚫 Block")
			.populateFriendInfo(friend)
			.build())
		message.addReaction("✅").queue()
		message.addReaction("❌").queue()
		message.addReaction("🚫").queue()
		val choice = message.awaitReactions({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
			max = 1
			time = 30000
			errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		}).await().first().reactionEmote.name
		return when (choice) {
			"✅" -> accept(source, user)
			"❌" -> reject(source, user)
			"🚫" -> block(source, user)
			else -> throw SimpleCommandExceptionType(LiteralMessage("Invalid input.")).create()
		}
	}

	private fun outgoing(source: CommandSourceStack, friend: FriendV2, user: GameProfile): Int {
		val message = source.complete(null, source.createEmbed()
			.setTitle("You have a pending friend request to ${friend.displayName?.escapeMarkdown() ?: friend.accountId}")
			.setDescription("❌ Cancel\n🚫 Block")
			.populateFriendInfo(friend)
			.build())
		message.addReaction("❌").queue()
		message.addReaction("🚫").queue()
		val choice = message.awaitReactions({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
			max = 1
			time = 30000
			errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		}).await().first().reactionEmote.name
		return when (choice) {
			"❌" -> cancel(source, user)
			"🚫" -> block(source, user)
			else -> throw SimpleCommandExceptionType(LiteralMessage("Invalid input.")).create()
		}
	}

	private fun blocked(source: CommandSourceStack, friend: FriendV2, user: GameProfile): Int {
		val message = source.complete(null, source.createEmbed()
			.setTitle("You have ${friend.displayName?.escapeMarkdown() ?: friend.accountId} blocked")
			.setDescription("🔓 Unblock")
			.populateFriendInfo(friend)
			.build())
		message.addReaction("🔓").queue()
		val choice = message.awaitReactions({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
			max = 1
			time = 30000
			errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		}).await().first().reactionEmote.name
		return when (choice) {
			"🔓" -> unblock(source, user)
			else -> throw SimpleCommandExceptionType(LiteralMessage("Invalid input.")).create()
		}
	}

	fun promptToSendFriendRequest(source: CommandSourceStack, user: GameProfile): Int {
		val message = source.complete(null, source.createEmbed()
			.setTitle("You're not friends with ${user.displayName}")
			.setDescription("📩 Send friend request\n🚫 Block")
			.build())
		message.addReaction("📩").queue()
		message.addReaction("🚫").queue()
		val choice = message.awaitReactions({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
			max = 1
			time = 30000
			errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		}).await().first().reactionEmote.name
		return when (choice) {
			"📩" -> add(source, user)
			"🚫" -> block(source, user)
			else -> throw SimpleCommandExceptionType(LiteralMessage("Invalid input.")).create()
		}
	}

	private fun add(source: CommandSourceStack, user: GameProfile): Int {
		source.loading("Sending friend request to ${user.displayName}")
		source.api.friendsService.sendInviteOrAcceptInvite(source.api.currentLoggedIn.id, user.id).exec()
		source.complete(null, source.createEmbed().setTitle("✅ Sent friend request to ${user.displayName}").build())
		return Command.SINGLE_SUCCESS
	}

	private fun remove(source: CommandSourceStack, user: GameProfile): Int {
		source.loading("Unfriending ${user.displayName}")
		source.api.friendsService.deleteFriendOrRejectInvite(source.api.currentLoggedIn.id, user.id).exec()
		source.complete(null, source.createEmbed().setTitle("✅ You're no longer friends with ${user.displayName}").build())
		return Command.SINGLE_SUCCESS
	}

	private fun accept(source: CommandSourceStack, user: GameProfile): Int {
		source.loading("Accepting ${user.displayName}'s friend request")
		source.api.friendsService.sendInviteOrAcceptInvite(source.api.currentLoggedIn.id, user.id).exec()
		source.complete(null, source.createEmbed().setTitle("✅ ${user.displayName} is now your friend").build())
		return Command.SINGLE_SUCCESS
	}

	private fun reject(source: CommandSourceStack, user: GameProfile): Int {
		source.loading("Rejecting ${user.displayName}'s friend request")
		source.api.friendsService.deleteFriendOrRejectInvite(source.api.currentLoggedIn.id, user.id).exec()
		source.complete(null, source.createEmbed().setTitle("✅ Rejected friend request from ${user.displayName}").build())
		return Command.SINGLE_SUCCESS
	}

	private fun cancel(source: CommandSourceStack, user: GameProfile): Int {
		source.loading("Canceling friend request to ${user.displayName}")
		source.api.friendsService.deleteFriendOrRejectInvite(source.api.currentLoggedIn.id, user.id).exec()
		source.complete(null, source.createEmbed().setTitle("✅ Cancelled outgoing friend request to ${user.displayName}").build())
		return Command.SINGLE_SUCCESS
	}

	private fun block(source: CommandSourceStack, user: GameProfile): Int {
		source.loading("Blocking ${user.displayName}")
		source.api.friendsService.sendBlock(source.api.currentLoggedIn.id, user.id).exec()
		source.complete(null, source.createEmbed().setTitle("✅ Blocked ${user.displayName}").build())
		return Command.SINGLE_SUCCESS
	}

	private fun unblock(source: CommandSourceStack, user: GameProfile): Int {
		source.loading("Unblocking ${user.displayName}")
		source.api.friendsService.sendUnblock(source.api.currentLoggedIn.id, user.id).exec()
		source.complete(null, source.createEmbed().setTitle("✅ Unblocked ${user.displayName}").build())
		return Command.SINGLE_SUCCESS
	}

	private fun EmbedBuilder.populateFriendInfo(friend: FriendV2): EmbedBuilder {
		//addField("Epic Display Name", friend.displayName, false)
		addField("Account ID", friend.accountId, false)
		if (friend.connections != null) {
			for ((k, v) in friend.connections) {
				addField(L10N.format("account.ext.$k.name"), v.name.orDash(), true)
			}
		}
		if (friend.created != null) {
			setFooter("Friends since")
			setTimestamp(friend.created.toInstant())
		}
		return this
	}

	private inline fun String?.orUnset() = if (isNullOrEmpty()) "(unset)" else this
}