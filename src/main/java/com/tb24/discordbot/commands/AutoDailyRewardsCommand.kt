package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.AutoClaimEntry
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.getUsers
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.users
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.commands.QueryPublicProfile
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.EmbedBuilder

class AutoDailyRewardsCommand : BrigadierCommand("autodaily", "Enroll/unenroll your saved accounts for automatic daily rewards claiming.", arrayOf("autostw")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source, null) }
		.then(argument("saved account name", users(1))
			.executes { execute(it.source, getUsers(it, "saved account name").values.first()) }
		)

	fun execute(source: CommandSourceStack, user: GameProfile?): Int {
		var accountId = user?.id
		var user = user
		val discordId = source.author.id
		val autoClaimEntries = r.table("auto_claim").run(source.client.dbConn, AutoClaimEntry::class.java).toList()
		val devices = source.client.savedLoginsManager.getAll(source.session.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved logins. Please perform `.savelogin` before continuing.")).create()
		}
		if (user == null) {
			val users = source.queryUsers(devices.map { it.accountId })
			source.complete(null, EmbedBuilder()
				.setTitle("Auto STW daily rewards claiming")
				.setDescription("Enroll/unenroll an account by typing the entry number. ⏱ 30s")
				.addField("Your saved accounts", devices.mapIndexed { i, it ->
					val id = devices[i].accountId
					"${Formatters.num.format(i + 1)}. ${users.firstOrNull { it.id == id }?.displayName ?: id} ${if (autoClaimEntries.any { it.id == id && it.registrantId == discordId }) " ✅" else ""}"
				}.joinToString("\n"), false)
				.setColor(0x8AB4F8)
				.build())
			val choice = source.channel.awaitMessages({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitMessagesOptions().apply {
				max = 1
				time = 30000
				errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
			}).await().first().contentRaw.toIntOrNull()
				?: throw SimpleCommandExceptionType(LiteralMessage("The provided choice is not a number.")).create()
			val selectedDevice = devices.getOrNull(choice - 1)
				?: throw SimpleCommandExceptionType(LiteralMessage("Invalid choice.")).create()
			accountId = selectedDevice.accountId
			user = users.firstOrNull { it.id == accountId }
				?: throw SimpleCommandExceptionType(LiteralMessage("That account was not found in Epic's database.")).create()
		} else if (!devices.any { it.accountId == accountId }) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have that account saved.")).create()
		}
		check(accountId != null)
		if (!autoClaimEntries.any { it.id == accountId && it.registrantId == discordId }) {
			source.loading("Checking STW ownership and enrolling")
			if (autoClaimEntries.any { it.id == accountId }) {
				throw SimpleCommandExceptionType(LiteralMessage("Another user of ${source.message.jda.selfUser.name} already have that account enrolled for auto claiming. An Epic account can only be enrolled once throughout the whole bot.")).create()
			}
			source.api.profileManager.dispatchPublicCommandRequest(user, QueryPublicProfile(), "campaign").await()
			val campaign = source.api.profileManager.getProfileData(user.id, "campaign")
			val completedTutorial = campaign.items.values.firstOrNull { it.templateId == "Quest:homebaseonboarding" }?.attributes?.get("completion_hbonboarding_completezone")?.asInt ?: 0 > 0
			if (!completedTutorial) {
				throw SimpleCommandExceptionType(LiteralMessage("The account must own Save the World and completed the tutorial in order to start receiving daily rewards.")).create()
			}
			r.table("auto_claim").insert(AutoClaimEntry(accountId, discordId)).run(source.client.dbConn)
			val millisInDay = 24L * 60L * 60L * 1000L
			val nextUtcMidnight = (System.currentTimeMillis() / millisInDay + 1) * millisInDay
			source.complete(null, EmbedBuilder()
				.setTitle("✅ Successfully enrolled auto daily rewards claiming for account `${user.displayName ?: accountId}`")
				.setDescription("${source.message.jda.selfUser.name} will automatically claim it after UTC midnight.")
				.addField("Next claim", nextUtcMidnight.relativeFromNow(), false)
				.setColor(COLOR_SUCCESS)
				.build())
		} else {
			if (autoClaimEntries.any { it.id == accountId && it.registrantId != discordId }) {
				throw SimpleCommandExceptionType(LiteralMessage("Cannot unenroll because that account wasn't enrolled by you.")).create()
			}
			r.table("auto_claim").get(accountId).delete().run(source.client.dbConn)
			source.complete(null, EmbedBuilder()
				.setTitle("✅ Successfully unenrolled auto daily rewards claiming for account `${user.displayName ?: accountId}`.")
				.setDescription("${source.message.jda.selfUser.name} will no longer automatically claim the daily rewards of that account.")
				.setColor(COLOR_SUCCESS)
				.build())
		}
		return Command.SINGLE_SUCCESS
	}
}