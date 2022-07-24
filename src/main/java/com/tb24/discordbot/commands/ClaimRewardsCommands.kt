package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.OpenCardPackBatch
import com.tb24.fn.model.mcpprofile.commands.subgame.ClaimQuestReward
import com.tb24.fn.model.mcpprofile.notifications.CardPackResultNotification
import com.tb24.fn.model.mcpprofile.notifications.MissionAlertCompleteNotification
import com.tb24.fn.model.mcpprofile.notifications.QuestClaimNotification
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.fn.util.getString
import net.dv8tion.jda.api.EmbedBuilder

class ClaimQuestRewardCommand : BrigadierCommand("claimquest", "Claims quest rewards.", arrayOf("cq")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { exec(it.source) }
		.then(literal("bulk")
			.executes { bulk(it.source, null) }
			.then(argument("users", UserArgument.users(100))
				.executes { bulk(it.source, UserArgument.getUsers(it, "users", loadingText = null)) }
			)
		)

	private fun exec(source: CommandSourceStack, loadingMessage: Boolean = true): Int {
		source.ensureSession()
		if (loadingMessage) {
			source.loading("Claiming quest rewards")
		}
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = source.api.profileManager.getProfileData("campaign")
		val questsToClaim = campaign.items.values.filter { it.templateId.startsWith("Quest:", true) && it.attributes.getString("quest_state") == "Completed" }
		if (questsToClaim.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No quest rewards to claim.")).create()
		}
		val embed = source.createEmbed().setTitle("✅ Rewards claimed")
		val isFounder = campaign.items.values.any { it.templateId.equals("Token:receivemtxcurrency", true)}
		for (quest in questsToClaim) {
			val claimed = source.api.profileManager.dispatchClientCommandRequest(ClaimQuestReward().apply {
				questId = quest.itemId
			}, "campaign").await()
			claimed.notifications.forEach {
				(it as QuestClaimNotification).also {
					embed.addField(FortItemStack().apply { templateId = it.questId }.displayName, it.loot.items.joinToString("\n") {
						if (it.itemType == "AccountResource:currency_mtxswap" && isFounder) {
							it.itemType = "Currency:MtxGiveaway"
						}
						it.asItemStack().render()
					}, false)
				}
			}
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun bulk(source: CommandSourceStack, users: Map<String, GameProfile>?): Int {
		val entries = stwBulk(source, if (users != null) lazy { users.values } else null) { campaign ->
			val questsToClaim = campaign.items.values.filter { it.templateId.startsWith("Quest:", true) && it.attributes.getString("quest_state") == "Completed" }
			if (questsToClaim.isEmpty()) {
				return@stwBulk null
			}
			campaign.owner.id
		}
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No devices.")).create()
		}
		if (entries.isEmpty() || devices.none { it.accountId in entries }) {
			throw SimpleCommandExceptionType(LiteralMessage("No quest rewards to claim.")).create()
		}
		forEachSavedAccounts(source, devices.filter { it.accountId in entries }) {
			exec(source, false)
		}
		return Command.SINGLE_SUCCESS
	}
}

class ClaimMissionAlertRewards : BrigadierCommand("claimalert", "Claim pending mission alert rewards", arrayOf("cal")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			exec(it.source)
		}
		.then(literal("bulk")
			.executes { bulk(it.source) }
			.then(argument("users", UserArgument.users(100))
				.executes { bulk(it.source, UserArgument.getUsers(it, "users")) }
			)
		)

	private fun exec(source: CommandSourceStack, loadingMessage: Boolean = true): Int {
		source.ensureSession()
		if (loadingMessage) {
			source.loading("Claiming mission alert rewards")
		}
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = source.api.profileManager.getProfileData("campaign")
		if ((campaign.stats as CampaignProfileStats).mission_alert_redemption_record?.pendingMissionAlertRewards?.items.isNullOrEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No mission alert rewards to claim.")).create()
		}
		val embed = source.createEmbed().setTitle("✅ Rewards claimed")
		val isFounder = campaign.items.values.any { it.templateId.equals("Token:receivemtxcurrency", true)}
		val claimed = source.api.profileManager.dispatchClientCommandRequest(ClaimMissionAlertRewards(), "campaign").await()
		val rewards = mutableListOf<String>()
		claimed.notifications.forEach {
			(it as MissionAlertCompleteNotification).also {
				it.lootGranted.items.forEach {
					if (it.itemType == "AccountResource:currency_mtxswap" && isFounder) {
						it.itemType = "Currency:MtxGiveaway"
					}
					rewards.add(it.asItemStack().render())
				}
			}
		}
		source.complete(null, embed.setDescription(rewards.joinToString("\n")).build())
		return Command.SINGLE_SUCCESS
	}

	private fun bulk(source: CommandSourceStack, users: Map<String, GameProfile>? = null): Int {
		val entries = stwBulk(source, if (users != null) lazy { users.values } else null) { campaign ->
			if ((campaign.stats as CampaignProfileStats).mission_alert_redemption_record?.pendingMissionAlertRewards?.items.isNullOrEmpty()) {
				return@stwBulk null
			}
			campaign.owner.id
		}
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No devices.")).create()
		}
		if (entries.isEmpty() || devices.none { it.accountId in entries }) {
			throw SimpleCommandExceptionType(LiteralMessage("No mission alert rewards to claim.")).create()
		}
		forEachSavedAccounts(source, devices.filter { it.accountId in entries }) {
			exec(source, false)
		}
		return Command.SINGLE_SUCCESS
	}
}

class CardPackChestCommand : BrigadierCommand("chest", "Open chest rewards", arrayOf("ce")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { preview(it.source) }
		.then(literal("open")
			.executes { execute(it.source) }
			.then(argument("chest num", IntegerArgumentType.integer(1))
				.executes { execute(it.source, IntegerArgumentType.getInteger(it, "chest num")) }
			)
			.then(literal("bulk")
				.executes { execOpenBulk(it.source, null) }
				.then(argument("users", UserArgument.users(100))
					.executes { execOpenBulk(it.source, UserArgument.getUsers(it, "users", loadingText = null)) }
				)
			)
		)
		.then(literal("bulk")
			.executes { execPreviewBulk(it.source, null) }
			.then(argument("users", UserArgument.users(100))
				.executes { execPreviewBulk(it.source, lazy { UserArgument.getUsers(it, "users", loadingText = null).values }) }
			)
		)

	private fun execute(source: CommandSourceStack, openNum: Int = -1, loadingMessage: Boolean = true): Int {
		val contents = getContents(source, "CardPack:zcp", "No chest.", loadingMessage)
		val opened = if (openNum == -1) {
			source.api.profileManager.dispatchClientCommandRequest(OpenCardPackBatch().apply {
				cardPackItemIds = contents.keys.toTypedArray()
			}, "campaign").await()
		} else {
			if (openNum > contents.size) {
				throw SimpleCommandExceptionType(LiteralMessage("Invalid chest number")).create()
			}
			source.api.profileManager.dispatchClientCommandRequest(OpenCardPackBatch().apply {
				cardPackItemIds = listOf(contents.keys.toTypedArray()[openNum-1]).toTypedArray()
			}, "campaign").await()
		}
		val loot = (opened.notifications.first() as CardPackResultNotification).lootGranted.items.map {
			it.asItemStack()
		}.sortedBy { it.displayName }
		val lootJoined = loot.groupBy { it.displayName }.map {
			val count = it.value.sumOf { it.quantity }
			FortItemStack(it.value.first().templateId, count)
		}.filterNot { it.quantity == 0 }
		source.complete(null, source.createEmbed().setTitle("Chest opened").apply {
			addFieldSeparate("Received", lootJoined) {
				it.render(showType = true)
			}
		}.build())
		return Command.SINGLE_SUCCESS
	}

	private fun preview(source: CommandSourceStack): Int {
		val contents = getContents(source, "CardPack:zcp", "No chest.")
		val text = mutableListOf<String>()
		contents.values.forEachIndexed { index, it ->
			val wave = if (it.templateId.startsWith("CardPack:zcp_endurance_", true)) it.attributes.get("override_loot_tier").toString() else ""
			text.add("`%,d` %s%s".format(index+1, if (wave.isNotEmpty()) "Wave %s ".format(wave) else "", it.displayName.substringAfter("[ph] ")))
		}
		val embed = source.createEmbed()
			.setTitle("Chest preview")
			.setDescription(text.joinToString("\n"))
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun execPreviewBulk(source: CommandSourceStack, usersLazy: Lazy<Collection<GameProfile>>? = null): Int {
		source.conditionalUseInternalSession()
		source.loading("Getting chest contents")
		val entries = stwBulk(source, usersLazy) { campaign ->
			val filtered = campaign.items.filter { it.value.templateId.startsWith("CardPack:zcp", false) }
			if (filtered.isEmpty()) {
				return@stwBulk null
			}
			val text = mutableListOf<String>()
			filtered.values.forEach {
				val wave = if (it.templateId.startsWith("CardPack:zcp_endurance_", true)) it.attributes.get("override_loot_tier").toString() else ""
				text.add("%s%s".format(if (wave.isNotEmpty()) "Wave %s ".format(wave) else "", it.displayName.substringAfter("[ph] ")))
			}
			val finalText = text.groupBy { it }.map { it.value.size to it.key }.joinToString("\n") { "**${it.first} ×** ${it.second}" }
			campaign.owner.displayName to finalText
		}
		if (entries.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No chests.")).create()
		}
		val embed = EmbedBuilder().setColor(COLOR_INFO)
		for (entry in entries) {
			if (embed.fields.size == 25) {
				source.complete(null, embed.build())
				embed.clearFields()
			}
			embed.addField(entry.first, entry.second, false)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun execOpenBulk(source: CommandSourceStack, users: Map<String, GameProfile>? = null): Int {
		val entries = stwBulk(source, if (users != null) lazy { users.values } else null) { campaign ->
			val filtered = campaign.items.filter { it.value.templateId.startsWith("CardPack:zcp", false) }
			if (filtered.isEmpty()) {
				return@stwBulk null
			}
			campaign.owner.id
		}
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No devices.")).create()
		}
		if (entries.isEmpty() || devices.none { it.accountId in entries }) {
			throw SimpleCommandExceptionType(LiteralMessage("No chests.")).create()
		}
		forEachSavedAccounts(source, devices.filter { it.accountId in entries }) {
			try {
				execute(source, loadingMessage = false)
			} catch (e: Exception) {
				source.channel.sendMessage(e.message.toString())
			}
		}
		return Command.SINGLE_SUCCESS
	}
}

class ClaimCacheCommand : BrigadierCommand("claimcache", "Claims pending cache", arrayOf("cc")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { exec(it.source) }
		.then(literal("bulk")
			.executes { bulk(it.source) }
			.then(argument("users", UserArgument.users(100))
				.executes { bulk(it.source, UserArgument.getUsers(it, "users", loadingText = null)) }
			)
		)

	private fun exec(source: CommandSourceStack, loadingMessage: Boolean = true): Int {
		val contents = getContents(source, "CardPack:cardpack_cache", "No caches.", loadingMessage)
		val opened = source.api.profileManager.dispatchClientCommandRequest(OpenCardPackBatch().apply {
			cardPackItemIds = contents.keys.toTypedArray()
		}, "campaign").await()
		val loot = (opened.notifications.first() as CardPackResultNotification).lootGranted.items.map {
			it.asItemStack()
		}.sortedBy { it.displayName }
		val lootJoined = loot.groupBy { it.displayName }.map {
			val count = it.value.sumOf { it.quantity }
			FortItemStack(it.value.first().templateId, count)
		}.filterNot { it.quantity == 0 }
		source.complete(null, source.createEmbed().setTitle("Caches claimed").apply {
			addFieldSeparate("Received", lootJoined) {
				it.render(showType = true)
			}
		}.build())
		return Command.SINGLE_SUCCESS
	}

	private fun bulk(source: CommandSourceStack, users: Map<String, GameProfile>? = null): Int {
		val entries = stwBulk(source, if (users != null) lazy { users.values } else null) { campaign ->
			val caches = campaign.items.values.filter { it.templateId.startsWith("CardPack:cardpack_cache", true) }
			if (caches.isEmpty()) {
				return@stwBulk null
			}
			campaign.owner.id
		}
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No devices.")).create()
		}
		if (entries.isEmpty() || devices.none { it.accountId in entries }) {
			throw SimpleCommandExceptionType(LiteralMessage("No caches.")).create()
		}
		forEachSavedAccounts(source, devices.filter { it.accountId in entries }) {
			exec(source, false)
		}
		return Command.SINGLE_SUCCESS
	}
}

class ClaimAllRewardsCommad : BrigadierCommand("claimall", "Claims all pending rewards/chests", arrayOf("ca")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			it.source.ensureSession()
			it.source.client.commandManager.handleCommand("cq", it.source, canRetry = false)
			it.source.client.commandManager.handleCommand("cal", it.source, canRetry = false)
			it.source.client.commandManager.handleCommand("ce open", it.source, canRetry = false)
			it.source.client.commandManager.handleCommand("cc", it.source, canRetry = false)
			Command.SINGLE_SUCCESS
		}
}

private fun getContents(source: CommandSourceStack, filterPrefix: String, errorMessage: String, loadingMessage: Boolean = true): Map<String, FortItemStack> {
	source.ensureSession()
	if (loadingMessage) {
		source.loading("Querying profile data")
	}
	source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
	val campaign = source.api.profileManager.getProfileData("campaign")
	val filtered = campaign.items.filter { it.value.templateId.startsWith(filterPrefix, false) }
	if (filtered.isEmpty()) {
		throw SimpleCommandExceptionType(LiteralMessage(errorMessage)).create()
	}
	return filtered
}