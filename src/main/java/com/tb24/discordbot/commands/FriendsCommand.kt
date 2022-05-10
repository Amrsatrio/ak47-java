package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.BoolArgumentType.getBool
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.L10N
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.getUsers
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.users
import com.tb24.discordbot.managers.PartyManager
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.friends.FriendV2
import com.tb24.fn.model.friends.FriendsSettings
import com.tb24.fn.model.friends.FriendsSummary
import com.tb24.fn.model.party.FMemberInfo
import com.tb24.fn.model.party.FPartyInfo
import com.tb24.fn.network.FriendsService
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import java.net.HttpURLConnection

class FriendsCommand : BrigadierCommand("friends", "Epic Friends operations.", arrayOf("f")) {
	companion object {
		const val TOP_MUTUALS_COUNT = 15
	}

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
		.then(literal("allowrequests")
			.executes { updateAcceptInvites(it.source) }
			.then(argument("can receive requests?", bool())
				.executes { updateAcceptInvites(it.source, getBool(it, "can receive requests?")) }
			)
		)
		.then(literal("cleanup").executes { cleanup(it.source) })
		.then(argument("user", users())
			.executes { c ->
				val source = c.source
				source.ensureSession()
				val summary = source.api.friendsService.queryFriendsSummary(source.api.currentLoggedIn.id, true).exec().body()!!
				val users = getUsers(c, "user", summary.friends)
				if (users[source.api.currentLoggedIn.id] != null) {
					throw SimpleCommandExceptionType(LiteralMessage("Users cannot be friends with themselves")).create()
				}
				if (users.size > 1) {
					bulk(source, "add", users.values.toList(), null, FriendsService::sendInviteOrAcceptInvite)
				} else {
					entryDetails(source, users.values.first(), summary)
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
		val unsortedEntries = call.exec().body()!!
		source.queryUsers_map(unsortedEntries.map { it.accountId })
		val entries = unsortedEntries.sortedFriends(source)
		if (entries.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("$type is empty")).create()
		}
		source.replyPaginated(entries, 30) { content, page, pageCount ->
			val entriesStart = page * 30 + 1
			var entriesEnd = entriesStart
			var chunkStart = entriesStart
			val chunks = content.chunked(15) { chunk ->
				MessageEmbed.Field("%,d - %,d".format(chunkStart, (chunkStart + chunk.size).also { chunkStart = it } - 1), chunk.joinToString("\n") {
					val alias = it.alias
					val displayName = it.getDisplayName(source)
					"%,d. %s%s".format(entriesEnd++, when {
						!alias.isNullOrEmpty() -> if (withId) "${alias.escapeMarkdown()} (${displayName.escapeMarkdown()}) `${it.accountId}`" else "${alias.escapeMarkdown()} (${displayName.escapeMarkdown()})"
						!displayName.isNullOrEmpty() -> if (withId) "${displayName.escapeMarkdown()} `${it.accountId}`" else displayName.escapeMarkdown()
						else -> "`${it.accountId}`"
					}, if (type != "friends" || it.created == null || System.currentTimeMillis() >= it.created.time + 2L * 24L * 60L * 60L * 1000L) "" else "*")
				}, true)
			}
			val embed = source.createEmbed()
				.setTitle(type)
				.setDescription("Showing %,d to %,d of %,d entries\n* = Not eligible for gifting".format(entriesStart, entriesEnd - 1, entries.size))
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			chunks.forEach(embed::addField)
			MessageBuilder(embed.build())
		}
		return Command.SINGLE_SUCCESS
	}

	private fun entryDetails(source: CommandSourceStack, user: GameProfile, summary: FriendsSummary): Int {
		val friends = summary.friends.associateBy { it.accountId }
		friends[user.id]?.let {
			return friends(FriendContext(source, it, user, friends))
		}
		summary.incoming.firstOrNull { it.accountId == user.id }?.let {
			return incoming(FriendContext(source, it, user, friends))
		}
		summary.outgoing.firstOrNull { it.accountId == user.id }?.let {
			return outgoing(FriendContext(source, it, user, friends))
		}
		// suggested here
		summary.blocklist.firstOrNull { it.accountId == user.id }?.let {
			return blocked(FriendContext(source, it, user, friends))
		}
		return promptToSendFriendRequest(FriendContext(source, null, user, friends))
	}

	private fun friends(ctx: FriendContext, partyInviteSent: Boolean = false): Int {
		val (source, friend, user) = ctx
		check(friend != null)
		val partyManager = source.session.getPartyManager(source.api.currentLoggedIn.id)
		val buttons = mutableListOf<Button>()
		if (!partyInviteSent) {
			partyManager.fetchParty()
			if (partyManager.partyInfo != null) {
				buttons.add(Button.of(ButtonStyle.SECONDARY, "invite", "Invite to party", Emoji.fromUnicode("📩")))
			}
		} else {
			buttons.add(Button.of(ButtonStyle.SECONDARY, "invite", "Party invite sent", Emoji.fromUnicode("✅")).asDisabled())
		}
		buttons.add(Button.of(ButtonStyle.SECONDARY, "alias", "Change nickname", Emoji.fromUnicode("🏷️")))
		//buttons.add(Button.of(ButtonStyle.SECONDARY, "note", "Change note", Emoji.fromUnicode("📝")))
		if (friend.mutual > TOP_MUTUALS_COUNT) {
			buttons.add(Button.of(ButtonStyle.SECONDARY, "mutuals", "Show mutual friends", Emoji.fromUnicode("🔗")))
		}
		buttons.add(Button.of(ButtonStyle.DANGER, "remove", "Remove friend", Emoji.fromUnicode("🗑")))
		buttons.add(Button.of(ButtonStyle.DANGER, "block", "Block", Emoji.fromUnicode("⛔")))
		val message = source.complete(null, source.createEmbed()
			.setTitle(ctx.titleOverride ?: "You're friends with ${user.displayName?.escapeMarkdown() ?: user.id}")
			.populateFriendInfo(ctx, true)
			.build(), *buttons.chunked(5, ActionRow::of).toTypedArray())
		source.loadingMsg = message
		return when (message.awaitOneInteraction(source.author, false).componentId) {
			"invite" -> inviteToParty(ctx, partyManager)
			"alias" -> aliasOrNote(ctx, friend.alias, false)
			"note" -> aliasOrNote(ctx, friend.note, true)
			"mutuals" -> mutuals(ctx, user.id)
			"remove" -> remove(ctx)
			"block" -> block(ctx)
			else -> throw AssertionError()
		}
	}

	private fun inviteToParty(ctx: FriendContext, party: PartyManager): Int {
		val (_, _, user) = ctx
		val invite = party.partyInfo!!.invites.firstOrNull { it.sent_to == user.id }
		if (invite != null) {
			party.ping(user.id)
		} else {
			party.invite(user.id)
		}
		return friends(ctx, true)
	}

	private fun aliasOrNote(ctx: FriendContext, old: String?, note: Boolean): Int {
		val (source, friend, user) = ctx
		ctx.source.loadingMsg?.finalizeComponents(setOf("alias", "note"))
		val propName = if (note) "note" else "nickname"
		val promptMsg = source.channel.sendMessage("The current $propName is: `${old.orUnset()}`\nEnter the new $propName, or `clear` to unset: (⏱ 60s)").complete()
		var new = source.channel.awaitMessages({ collected, _, _ -> collected.author == source.author }, AwaitMessagesOptions().apply {
			max = 1
			time = 60000L
			errors = arrayOf(CollectorEndReason.TIME)
		}).await().first().contentRaw
		val friendsService = source.api.friendsService
		val accountId = source.api.currentLoggedIn.id
		val friendId = user.id
		if (new == "clear") {
			new = ""
			if (note) {
				friendsService.deleteFriendNote(accountId, friendId).exec()
				friend!!.note = new
			} else {
				friendsService.deleteFriendAlias(accountId, friendId).exec()
				friend!!.alias = new
			}
		} else {
			val body = new.toRequestBody("text/plain".toMediaType())
			if (note) {
				friendsService.setFriendNote(accountId, friendId, body).exec()
				friend!!.note = new
			} else {
				friendsService.setFriendAlias(accountId, friendId, body).exec()
				friend!!.alias = new
			}
		}
		promptMsg.delete().queue()
		ctx.titleOverride = "✅ Updated $propName of ${user.displayName}"
		//ctx.descriptionExtra = "`${old.orUnset()}` \u2192 `${new.orUnset()}`"
		return friends(ctx)
	}

	private fun incoming(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		val buttons = mutableListOf<Button>()
		buttons.add(Button.of(ButtonStyle.SUCCESS, "accept", "Accept"))
		buttons.add(Button.of(ButtonStyle.SECONDARY, "reject", "Reject"))
		buttons.add(Button.of(ButtonStyle.DANGER, "block", "Block", Emoji.fromUnicode("⛔")))
		val message = source.complete(null, source.createEmbed()
			.setTitle(ctx.titleOverride ?: "Friend request from ${user.displayName?.escapeMarkdown() ?: user.id}")
			.populateFriendInfo(ctx)
			.build(), ActionRow.of(buttons))
		source.loadingMsg = message
		return when (message.awaitOneInteraction(source.author, false).componentId) {
			"accept" -> accept(ctx)
			"reject" -> reject(ctx)
			"block" -> block(ctx)
			else -> throw AssertionError()
		}
	}

	private fun outgoing(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		val buttons = mutableListOf<Button>()
		buttons.add(Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel friend request", Emoji.fromUnicode("❌")))
		buttons.add(Button.of(ButtonStyle.DANGER, "block", "Block", Emoji.fromUnicode("⛔")))
		val message = source.complete(null, source.createEmbed()
			.setTitle(ctx.titleOverride ?: "You have a pending friend request to ${user.displayName?.escapeMarkdown() ?: user.id}")
			.populateFriendInfo(ctx)
			.build(), ActionRow.of(buttons))
		source.loadingMsg = message
		return when (message.awaitOneInteraction(source.author, false).componentId) {
			"cancel" -> cancel(ctx)
			"block" -> block(ctx)
			else -> throw AssertionError()
		}
	}

	private fun blocked(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		val buttons = mutableListOf<Button>()
		buttons.add(Button.of(ButtonStyle.SECONDARY, "unblock", "Unblock", Emoji.fromUnicode("🔓")))
		val message = source.complete(null, source.createEmbed()
			.setTitle(ctx.titleOverride ?: "You have ${user.displayName?.escapeMarkdown() ?: user.id} blocked")
			.populateFriendInfo(ctx)
			.build(), ActionRow.of(buttons))
		source.loadingMsg = message
		return when (message.awaitOneInteraction(source.author, false).componentId) {
			"unblock" -> unblock(ctx)
			else -> throw AssertionError()
		}
	}

	private fun promptToSendFriendRequest(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		val mutuals = ctx.getMutualsFor(user.id)
		val buttons = mutableListOf<Button>()
		buttons.add(Button.of(ButtonStyle.SUCCESS, "request", "Send friend request", Emoji.fromUnicode("📩")))
		if (mutuals.size > TOP_MUTUALS_COUNT) {
			buttons.add(Button.of(ButtonStyle.SECONDARY, "mutuals", "Show mutual friends", Emoji.fromUnicode("🔗")))
		}
		buttons.add(Button.of(ButtonStyle.DANGER, "block", "Block", Emoji.fromUnicode("⛔")))
		val embed = source.createEmbed()
			.setTitle(ctx.titleOverride ?: "You're not friends with ${user.displayName}")
			.addField("Account ID", user.id, false)
			.populateTopMutuals(ctx, user.id)
		val (avatar, avatarBackground) = source.session.getAvatar(user.id)
		if (avatar.isNotEmpty()) {
			embed.setThumbnail(avatar)
		}
		if (avatarBackground != -1) {
			embed.setColor(avatarBackground)
		}
		val message = source.complete(null, embed.build(), ActionRow.of(buttons))
		source.loadingMsg = message
		return when (message.awaitOneInteraction(source.author, false).componentId) {
			"request" -> add(ctx)
			"mutuals" -> mutuals(ctx, user.id)
			"block" -> block(ctx)
			else -> throw AssertionError()
		}
	}

	private fun add(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		source.api.friendsService.sendInviteOrAcceptInvite(source.api.currentLoggedIn.id, user.id).exec()
		ctx.titleOverride = "✅ Sent friend request to ${user.displayName}"
		return outgoing(ctx)
	}

	private fun remove(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		source.api.friendsService.deleteFriendOrRejectInvite(source.api.currentLoggedIn.id, user.id).exec()
		ctx.titleOverride = "✅ You're no longer friends with ${user.displayName}"
		return promptToSendFriendRequest(ctx)
	}

	private fun accept(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		source.api.friendsService.sendInviteOrAcceptInvite(source.api.currentLoggedIn.id, user.id).exec()
		ctx.titleOverride = "✅ ${user.displayName} is now your friend"
		return friends(ctx)
	}

	private fun reject(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		source.api.friendsService.deleteFriendOrRejectInvite(source.api.currentLoggedIn.id, user.id).exec()
		ctx.titleOverride = "✅ Rejected friend request from ${user.displayName}"
		return promptToSendFriendRequest(ctx)
	}

	private fun cancel(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		source.api.friendsService.deleteFriendOrRejectInvite(source.api.currentLoggedIn.id, user.id).exec()
		ctx.titleOverride = "✅ Cancelled outgoing friend request to ${user.displayName}"
		return promptToSendFriendRequest(ctx)
	}

	private fun block(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		source.api.friendsService.sendBlock(source.api.currentLoggedIn.id, user.id).exec()
		ctx.titleOverride = "✅ Blocked ${user.displayName}"
		return blocked(ctx)
	}

	private fun unblock(ctx: FriendContext): Int {
		val (source, _, user) = ctx
		source.api.friendsService.sendUnblock(source.api.currentLoggedIn.id, user.id).exec()
		ctx.titleOverride = "✅ Unblocked ${user.displayName}"
		return promptToSendFriendRequest(ctx)
	}

	private fun bulk(source: CommandSourceStack, type: String, suppliedQueue: List<GameProfile>?, query: ((FriendsService, String, Boolean?) -> Call<Array<FriendV2>>)?, op: FriendsService.(String, String) -> Call<Void>): Int {
		if (suppliedQueue == null) {
			source.ensurePremium("Do bulk friend operations")
		}
		source.ensureSession()
		val confirmationMessage = source.complete(null, source.createEmbed().setColor(COLOR_WARNING)
			.setTitle("Confirmation")
			.setDescription(L10N.format("friends.$type.bulk.warning"))
			.build(), confirmationButtons())
		if (!confirmationMessage.awaitConfirmation(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
		source.loadingMsg = confirmationMessage
		val accountId = source.api.currentLoggedIn.id
		val usingSuppliedQueue = suppliedQueue != null
		var sub: Array<FriendV2>? = null
		val queue = if (usingSuppliedQueue) {
			suppliedQueue!!.map { it.id }
		} else {
			sub = query!!(source.api.friendsService, accountId, null).exec().body()!!
			sub.map { it.accountId }.also { source.queryUsers_map(it) }
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
					e.code() == 429 -> Thread.sleep((e.response.header("Retry-After")?.toIntOrNull() ?: throw e) * 1000L)
					e.code() == HttpURLConnection.HTTP_UNAUTHORIZED || e.epicError.errorCode == "errors.com.epicgames.friends.invitee_friendships_limit_exceeded" -> throw e
					else -> {
						val displayName = if (usingSuppliedQueue) {
							suppliedQueue!![i].displayName
						} else {
							sub!![i].getDisplayName(source)
						}
						source.channel.sendMessage("%s failed: %s".format(displayName?.escapeMarkdown() ?: queue[i], e.epicError.displayText)).queue()
						++i
					}
				}
			}
		}
		source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
			.setTitle("✅ " + TextFormatter.format(L10N.format("friends.$type.bulk.done"), mapOf("0" to success)))
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun updateAcceptInvites(source: CommandSourceStack, preferredState: Boolean? = null): Int {
		source.ensureSession()
		source.loading(if (preferredState != null)
			"Changing your friend request allowance"
		else
			"Toggling your friend request allowance")
		val currentState = source.api.friendsService.queryFriendSettings(source.api.currentLoggedIn.id).exec().body()!!.acceptInvites.equals("public", true)
		val newState = preferredState ?: !currentState
		if (newState == currentState) {
			throw SimpleCommandExceptionType(LiteralMessage(if (currentState)
				"Your account is already configured to **allow** friend requests."
			else
				"Your account is already configured to **disallow** friend requests.")).create()
		}
		source.api.friendsService.setFriendSettings(source.api.currentLoggedIn.id, FriendsSettings().apply {
			acceptInvites = if (newState) "PUBLIC" else "PRIVATE"
		}).exec()
		source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
			.setDescription("✅ " + if (newState)
				"Configured your account to **allow** friend requests."
			else
				"Configured your account to **disallow** friend requests.")
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun cleanup(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Gathering deleted accounts in your friend list")
		val summary = source.api.friendsService.queryFriendsSummary(source.api.currentLoggedIn.id, null).exec().body()!!

		// Check for account IDs that are still valid
		val ids = hashSetOf<String>()
		summary.friends.mapTo(ids) { it.accountId }
		summary.incoming.mapTo(ids) { it.accountId }
		summary.outgoing.mapTo(ids) { it.accountId }
		summary.blocklist.mapTo(ids) { it.accountId }
		source.queryUsers_map(ids)

		// Build list of calls
		val queue = mutableListOf<Pair<String, Call<Void>>>()
		summary.friends.filter { it.accountId !in source.userCache.keys }.mapTo(queue) { it.accountId to source.api.friendsService.deleteFriendOrRejectInvite(source.api.currentLoggedIn.id, it.accountId) }
		summary.incoming.filter { it.accountId !in source.userCache.keys }.mapTo(queue) { it.accountId to source.api.friendsService.deleteFriendOrRejectInvite(source.api.currentLoggedIn.id, it.accountId) }
		summary.outgoing.filter { it.accountId !in source.userCache.keys }.mapTo(queue) { it.accountId to source.api.friendsService.deleteFriendOrRejectInvite(source.api.currentLoggedIn.id, it.accountId) }
		summary.blocklist.filter { it.accountId !in source.userCache.keys }.mapTo(queue) { it.accountId to source.api.friendsService.sendUnblock(source.api.currentLoggedIn.id, it.accountId) }
		if (queue.isEmpty()) {
			source.complete(null, source.createEmbed()
				.setDescription("ℹ No deleted accounts in your friend list.")
				.build())
			return Command.SINGLE_SUCCESS
		}

		// Do them!
		var i = 0
		var success = 0
		while (i < queue.size) {
			source.loading("Cleaning up friend %,d of %,d...\n%s complete".format(i + 1, queue.size, Formatters.percentZeroFraction.format(i.toDouble() / queue.size)))
			val (accountId, call) = queue[i]
			try {
				call.exec()
				++i // do the next one if successful
				++success
			} catch (e: HttpException) {
				when {
					e.code() == 429 -> Thread.sleep((e.response.header("Retry-After")?.toIntOrNull() ?: throw e) * 1000L)
					e.code() == HttpURLConnection.HTTP_UNAUTHORIZED || e.epicError.errorCode == "errors.com.epicgames.friends.invitee_friendships_limit_exceeded" -> throw e
					else -> {
						source.channel.sendMessage("%s failed: %s".format(accountId, e.epicError.displayText)).queue()
						++i
					}
				}
			}
		}
		source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
			.setDescription(TextFormatter.format("✅ Cleaned {0} deleted {0}|plural(one=account,other=accounts) from your friend list!", mapOf("0" to success)))
			.build())

		return Command.SINGLE_SUCCESS
	}

	private fun EmbedBuilder.populateFriendInfo(ctx: FriendContext, alreadyFriends: Boolean = false): EmbedBuilder {
		val (source, friend, user) = ctx
		//addField("Epic Display Name", friend.displayName, false)
		addField("Account ID", user.id, false)
		setDescription(user.renderPublicExternalAuths().joinToString(" "))
		val alias = friend?.alias
		if (!alias.isNullOrEmpty()) {
			addField("Nickname", alias.escapeMarkdown(), false)
		}
		populateTopMutuals(ctx, user.id)
		val created = friend?.created
		if (created != null) {
			addField(if (alreadyFriends) "Friends since" else "Request sent at", created.relativeFromNow(), false)
			val canBeGiftedStart = created.time + 2L * 24L * 60L * 60L * 1000L
			if (alreadyFriends && System.currentTimeMillis() < canBeGiftedStart) {
				addField("Eligible for gifting", canBeGiftedStart.relativeFromNow(), false)
			}
		}
		val (avatar, avatarBackground) = source.session.getAvatar(user.id)
		if (avatar.isNotEmpty()) {
			setThumbnail(avatar)
		}
		if (avatarBackground != -1) {
			setColor(avatarBackground)
		}
		return this
	}

	private fun EmbedBuilder.populateTopMutuals(ctx: FriendContext, accountId: String): EmbedBuilder {
		val (source, _, _, friends) = ctx
		val mutuals = ctx.getMutualsFor(accountId)
		if (mutuals.isNotEmpty()) {
			val topMutuals = mutuals.map { friends[it]!! }.sortedByDescending { it.mutual }.take(TOP_MUTUALS_COUNT)
			val mutualsString = topMutuals.joinToString(", ") { "%s (%,d)".format(it.getDisplayName(source)?.escapeMarkdown() ?: "`${it.accountId}`", it.mutual) }
			addField("%,d mutual friends".format(mutuals.size), mutualsString, false)
		}
		return this
	}

	private fun mutuals(ctx: FriendContext, accountId: String): Int {
		val (source, _, _, friends) = ctx
		//source.loadingMsg?.finalizeComponents(setOf("mutuals"))
		val entries = ctx.getMutualsFor(accountId).sortedWith { a, b ->
			val numMutualsCompare = friends[b]!!.mutual - friends[a]!!.mutual
			if (numMutualsCompare != 0) {
				numMutualsCompare
			} else {
				val dnA = source.userCache[a]?.displayName ?: a
				val dnB = source.userCache[b]?.displayName ?: b
				dnA.compareTo(dnB, true)
			}
		}
		check(entries.isNotEmpty())
		source.replyPaginated(entries, 30) { content, page, pageCount ->
			val entriesStart = page * 30 + 1
			val entriesEnd = entriesStart + content.size
			var chunkStart = entriesStart
			val chunks = content.chunked(15) { chunk ->
				MessageEmbed.Field("%,d - %,d".format(chunkStart, (chunkStart + chunk.size).also { chunkStart = it } - 1), chunk.joinToString("\n") {
					"`%2d` %s".format(friends[it]!!.mutual, source.userCache[it]?.displayName?.escapeMarkdown() ?: "`$it`")
				}, true)
			}
			val embed = source.createEmbed()
				.setTitle("%s's mutual friends".format(source.userCache[accountId]?.displayName ?: "`$accountId`"))
				.setDescription("Showing %,d to %,d of %,d entries".format(entriesStart, entriesEnd - 1, entries.size))
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			chunks.forEach(embed::addField)
			MessageBuilder(embed.build())
		}
		return Command.SINGLE_SUCCESS
	}

	private inline fun String?.orUnset() = if (isNullOrEmpty()) "(unset)" else this

	private class FriendContext(
		@JvmField val source: CommandSourceStack,
		@JvmField val friend: FriendV2?,
		@JvmField val user: GameProfile,
		@JvmField val friends: Map<String, FriendV2>
	) {
		@JvmField var titleOverride: String? = null
		private val mutualsCache = hashMapOf<String, List<String>>()

		fun getMutualsFor(accountId: String) = mutualsCache.getOrPut(accountId) {
			val mutuals = source.api.friendsService.queryFriendMutuals(source.api.currentLoggedIn.id, accountId).exec().body()!!.toList()
			source.queryUsers_map(mutuals)
			mutuals
		}

		inline operator fun component1() = source
		inline operator fun component2() = friend
		inline operator fun component3() = user
		inline operator fun component4() = friends
		inline operator fun component5() = titleOverride
	}
}