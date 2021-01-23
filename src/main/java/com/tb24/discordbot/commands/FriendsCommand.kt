package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.L10N
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.getUsers
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.users
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.friends.FriendV2
import com.tb24.fn.model.friends.FriendsSummary
import com.tb24.fn.network.FriendsService
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import okhttp3.MediaType
import okhttp3.RequestBody
import retrofit2.Call
import java.net.HttpURLConnection
import java.util.concurrent.CompletableFuture

class FriendsCommand : BrigadierCommand("friends", "Epic Friends operations.", arrayOf("f")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { list(it.source, "friends") }.then(literal("withid").executes { list(it.source, "friends", true) })
		.then(literal("incoming").executes { list(it.source, "incoming") }.then(literal("withid").executes { list(it.source, "incoming", true) }))
		.then(literal("outgoing").executes { list(it.source, "outgoing") }.then(literal("withid").executes { list(it.source, "outgoing", true) }))
		.then(literal("blocklist").executes { list(it.source, "blocklist") }.then(literal("withid").executes { list(it.source, "blocklist", true) }))
		.then(literal("removeall").executes { bulk(it.source, "remove", null, FriendsService::queryFriends, FriendsService::deleteFriendOrRejectInvite) })
		.then(literal("acceptall").executes { bulk(it.source, "accept", null, FriendsService::queryIncomingFriendRequests, FriendsService::sendInviteOrAcceptInvite) })
		.then(literal("rejectall").executes { bulk(it.source, "reject", null, FriendsService::queryIncomingFriendRequests, FriendsService::deleteFriendOrRejectInvite) })
		.then(literal("cancelall").executes { bulk(it.source, "cancel", null, FriendsService::queryOutgoingFriendRequests, FriendsService::deleteFriendOrRejectInvite) })
		.then(literal("unblockall").executes { bulk(it.source, "unblock", null, FriendsService::queryBlockedPlayers, FriendsService::sendUnblock) })
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
			source.channel.sendFile(ids.toByteArray(), "avatar_ids_${source.api.currentLoggedIn.displayName}.txt").complete()
			Command.SINGLE_SUCCESS
		})
		.then(argument("user", users())
			.executes { c ->
				c.source.ensureSession()
				val summary = c.source.api.friendsService.queryFriendsSummary(c.source.api.currentLoggedIn.id, true).exec().body()!!
				val friends = summary.friends.sortedFriends()
				val users = getUsers(c, "user", friends)
				if (users.size > 1) {
					bulk(c.source, "add", users.values.toList(), null, FriendsService::sendInviteOrAcceptInvite)
				} else {
					entryDetails(c.source, users.values.first(), summary)
				}
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
		if (entries.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("$type is empty")).create()
		}
		source.message.replyPaginated(entries, 30, source.loadingMsg) { content, page, pageCount ->
			val entriesStart = page * 30 + 1
			var entriesEnd = entriesStart
			var chunkStart = entriesStart
			val chunks = content.chunked(15) { chunk ->
				MessageEmbed.Field("%,d - %,d".format(chunkStart, (chunkStart + chunk.size).also { chunkStart = it } - 1), chunk.joinToString("\n") {
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
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
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

	private fun promptToSendFriendRequest(source: CommandSourceStack, user: GameProfile): Int {
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

	private fun bulk(source: CommandSourceStack, type: String, suppliedQueue: List<GameProfile>?, query: ((FriendsService, String, Boolean?) -> Call<Array<FriendV2>>)?, op: FriendsService.(String, String) -> Call<Void>): Int {
		source.ensureSession()
		if (!source.complete(null, source.createEmbed()
				.setTitle("Confirmation")
				.setDescription(L10N.format("friends.$type.bulk.warning"))
				.setColor(0xFFF300)
				.build()).yesNoReactions(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
		val accountId = source.api.currentLoggedIn.id
		val usingSuppliedQueue = suppliedQueue != null
		var sub: Array<FriendV2>? = null
		val queue = if (usingSuppliedQueue) {
			suppliedQueue!!.map { it.id }
		} else {
			sub = query!!(source.api.friendsService, accountId, null).exec().body()!!
			sub.map { it.accountId }
		}
		var i = 0
		var success = 0
		while (i < queue.size) {
			source.loading(L10N.format("friends.$type.bulk", Formatters.num.format(i + 1), Formatters.num.format(queue.size), Formatters.percentZeroFraction.format(i.toDouble() / queue.size)))
			try {
				source.api.friendsService.op(accountId, queue[i]).exec()
				++i // do the next one if successful
				++success
			} catch (e: HttpException) {
				when {
					e.epicError.errorCode == "errors.com.epicgames.common.throttled" -> Thread.sleep(e.epicError.messageVars[0].toInt() * 1000L)
					e.code() == HttpURLConnection.HTTP_UNAUTHORIZED || e.epicError.errorCode == "errors.com.epicgames.friends.invitee_friendships_limit_exceeded" -> throw e
					else -> {
						val displayName = if (usingSuppliedQueue) {
							suppliedQueue!![i].displayName
						} else {
							sub!![i].displayName
						}
						source.channel.sendMessage("%s failed: %s".format(displayName?.escapeMarkdown() ?: queue[i], e.epicError.displayText)).queue()
						++i
					}
				}
			}
		}
		source.complete(null, source.createEmbed()
			.setTitle("✅ " + L10N.format("friends.$type.bulk.done", Formatters.num.format(success)))
			.build())
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
		if (!friend.alias.isNullOrEmpty()) {
			addField("Nickname", friend.alias.escapeMarkdown(), false)
		}
		if (friend.created != null) {
			val canBeGiftedStart = friend.created.time + 2L * 24L * 60L * 60L * 1000L
			if (System.currentTimeMillis() < canBeGiftedStart) {
				addField("Eligible for gifting", canBeGiftedStart.relativeFromNow(), false)
			}
			setFooter("Friends since")
			setTimestamp(friend.created.toInstant())
		}
		return this
	}

	private inline fun String?.orUnset() = if (isNullOrEmpty()) "(unset)" else this
}