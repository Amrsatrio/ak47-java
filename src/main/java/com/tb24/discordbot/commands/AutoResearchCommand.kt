package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.getUsers
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.users
import com.tb24.discordbot.model.AutoResearchEnrollment
import com.tb24.discordbot.tasks.ensureData
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.commands.QueryPublicProfile
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.EmbedBuilder

// TODO Duplicated AutoDailyRewardsCommand for now, refactor later
class AutoResearchCommand : BrigadierCommand("autoresearch", "Enroll/unenroll your saved accounts for automatic research.", arrayOf("ar")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.conditionalUseInternalSession()
			execute(source, null)
		}
		.then(argument("saved account name", users(1))
			.executes {
				val source = it.source
				source.conditionalUseInternalSession()
				execute(source, getUsers(it, "saved account name").values.first())
			}
		)
		.then(literal("emergencystop")
			.executes { executeEmergencyStop(it.source) }
		)

	private fun execute(source: CommandSourceStack, user: GameProfile?): Int {
		source.ensurePremium("Automatically research")
		var accountId = user?.id
		var user = user
		val discordId = source.author.id
		val autoClaimEntries = r.table("auto_research").run(source.client.dbConn, AutoResearchEnrollment::class.java).toList()
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved logins. Please perform `.savelogin` before continuing.")).create()
		}
		if (user == null) {
			val users = source.queryUsers(devices.map { it.accountId })
			source.complete(null, EmbedBuilder()
				.setTitle("Auto research")
				.setDescription("Enroll/unenroll an account by typing the account number. ⏱ 30s")
				.addField("Your saved accounts", List(devices.size) { i ->
					val id = devices[i].accountId
					val enrollment = autoClaimEntries.firstOrNull { it.id == id && it.registrantId == discordId }
					"${Formatters.num.format(i + 1)}. ${users.firstOrNull { it.id == id }?.displayName ?: id}${if (enrollment != null) " ✅ " + enrollment.nextRun.relativeFromNow() else ""}"
				}.joinToString("\n"), false)
				.setColor(0x8AB4F8)
				.build())
			val choice = source.channel.awaitMessages({ _, user, _ -> user == source.author }, AwaitMessagesOptions().apply {
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
				throw SimpleCommandExceptionType(LiteralMessage("Another user of ${source.jda.selfUser.name} already have that account enrolled for auto claiming. An Epic account can only be enrolled once throughout the whole bot.")).create()
			}
			source.api.profileManager.dispatchPublicCommandRequest(user, QueryPublicProfile(), "campaign").await()
			val data = AutoResearchEnrollment(accountId, discordId)
			check(data.ensureData(source))
			r.table("auto_research").insert(data).run(source.client.dbConn)
			source.client.autoResearchManager.schedule(data)
			source.complete(null, EmbedBuilder().setColor(COLOR_SUCCESS)
				.setTitle("✅ Enrolled auto research for account `${user.displayName ?: accountId}`")
				.setDescription("${source.jda.selfUser.name} will automatically research every time the collector reaches capacity.")
				.addField("Next run", data.nextRun?.relativeFromNow(), false)
				.build())
		} else {
			if (autoClaimEntries.any { it.id == accountId && it.registrantId != discordId }) {
				throw SimpleCommandExceptionType(LiteralMessage("Cannot unenroll because that account wasn't enrolled by you.")).create()
			}
			r.table("auto_research").get(accountId).delete().run(source.client.dbConn)
			source.client.autoResearchManager.unschedule(accountId)
			source.complete(null, EmbedBuilder().setColor(COLOR_SUCCESS)
				.setTitle("✅ Unenrolled auto research for account `${user.displayName ?: accountId}`.")
				.build())
		}
		return Command.SINGLE_SUCCESS
	}

	private fun executeEmergencyStop(source: CommandSourceStack): Int {
		source.ensurePremium("Automatically research")
		if (!source.complete(null, source.createEmbed().setColor(COLOR_WARNING)
				.setTitle("✋ Hold up!")
				.setDescription("By pressing \"Confirm\", you will prevent the auto research function from running further until the bot is restarted. This function should only be used if you are experiencing login or message spams related to auto research.\n**Please only use this feature if auto research misbehaves! Misuse of this feature will result in loss of your premium status!**\nContinue?")
				.build(), confirmationButtons()).awaitConfirmation(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
		source.client.autoResearchManager.emergencyStop()
		source.complete("🚨 You've pressed the emergency stop button for auto research. Please let the bot authors know about the issues you've been experiencing.")
		source.client.dlog("🛑 **Auto research emergency stopped by %s (%s)**".format(source.author.asMention, source.author.id), null)
		return Command.SINGLE_SUCCESS
	}
}