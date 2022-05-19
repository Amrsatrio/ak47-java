package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.getSentGiftsWithin24H
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import java.util.*

class GiftHistoryCommand : BrigadierCommand("gifthistory", "Displays how much gifting slots you have left along with a partial history of sent/received gifts.", arrayOf("gh")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { summary(it.source) }
		.then(literal("sent").executes { detail(it.source, false) })
		.then(literal("received").executes { detail(it.source, true) })
		.then(literal("bulk").executes { bulk(it.source, null) }.then(argument("users", UserArgument.users(100)).executes { bulk(it.source, UserArgument.getUsers(it, "users", loadingText = null)) }))

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("summary", description).executes { summary(it) })
		.then(subcommand("sent", "Lists partial recipients and dates of sent gifts").executes { detail(it, false) })
		.then(subcommand("received", "Lists partial senders and dates of received gifts").executes { detail(it, true) })

	private fun summary(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting gift history")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
		val commonCore = source.api.profileManager.getProfileData("common_core")
		val giftHistory = (commonCore.stats as CommonCoreProfileStats).gift_history
		val sentTo = giftHistory.sentTo ?: emptyMap()
		val receivedFrom = giftHistory.receivedFrom ?: emptyMap()
		val gifts = giftHistory.gifts ?: emptyArray()
		val idsToQuery = mutableSetOf<String>()
		idsToQuery.addAll(sentTo.keys)
		idsToQuery.addAll(receivedFrom.keys)
		gifts.forEach { idsToQuery.add(it.toAccountId) }
		source.queryUsers_map(idsToQuery)
		source.client.catalogManager.ensureCatalogData(source.client.internalSession.api)
		val within24h = getSentGiftsWithin24H(giftHistory)
		val sb = StringBuilder(if (within24h.size < 5) {
			"**%,d** daily gifts remaining.".format(5 - within24h.size)
		} else {
			"Daily gifts limit reached."
		})
		if (within24h.isNotEmpty()) {
			sb.append("\n**Next free slots:** ")
			within24h.joinTo(sb) { Date(it.date.time + 24L * 60L * 60L * 1000L).relativeFromNow() }
		}
		sb.append("\n**Total sent:** %,d\n**Total received:** %,d\nThe data below are partial.".format(giftHistory.num_sent, giftHistory.num_received))
		source.complete(null, source.createEmbed()
			.setTitle("Gift History")
			.setDescription(sb.toString())
			.addField("Sent (${Formatters.num.format(sentTo.size)})", preview(sentTo, source.userCache, source.prefix + source.commandName + " sent"), false)
			.addField("Received (${Formatters.num.format(receivedFrom.size)})", preview(receivedFrom, source.userCache, source.prefix + source.commandName + " received"), false)
			.addFieldSeparate("Recent gifts (${Formatters.num.format(gifts.size)})", gifts.sortedByDescending { it.date }) { e ->
				val catalogEntry = source.client.catalogManager.purchasableCatalogEntries.firstOrNull { it.offerId == e.offerId }
				"${catalogEntry?.holder()?.friendlyName ?: "<Item outside of current shop>"} to ${source.userCache[e.toAccountId]?.displayName ?: e.toAccountId} on ${e.date.format()}"
			}
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun preview(data: Map<String, Date>, localUserMap: MutableMap<String, GameProfile>, commandHint: String, limit: Int = 10): String {
		val lines = mutableListOf<String>()
		for ((i, o) in data.entries.sortedByDescending { it.value }.withIndex()) {
			if (i >= limit) {
				lines.add("... ${Formatters.num.format(data.size - limit)} more, `$commandHint` to show more")
				break
			}
			lines.add(renderUserDate(o, localUserMap))
		}
		return lines.joinToString("\n")
	}

	private fun detail(source: CommandSourceStack, isReceive: Boolean): Int {
		source.ensureSession()
		source.loading("Getting gift history")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
		val commonCore = source.api.profileManager.getProfileData("common_core")
		val giftHistory = (commonCore.stats as CommonCoreProfileStats).gift_history
		val data = (if (isReceive) giftHistory?.receivedFrom else giftHistory?.sentTo) ?: emptyMap()
		val localUserMap = mutableMapOf<String, GameProfile>()
		val idsToQuery = mutableSetOf<String>()
		idsToQuery.addAll(data.keys)
		if (idsToQuery.size > 0) {
			source.queryUsers(idsToQuery).forEach { localUserMap[it.id] = it }
		}
		source.replyPaginated(data.entries.sortedByDescending { it.value }, 20) { content, page, pageCount ->
			MessageBuilder(source.createEmbed()
				.setTitle("Gift History / ${if (isReceive) "Received" else "Sent"}")
				.setDescription("Showing ${page * 20 + 1} to ${page * 20 + content.size} of ${data.size} entries" + "\n\n" + content.joinToString("\n") { "\u2022 ${renderUserDate(it, localUserMap)}" })
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			)
		}
		return Command.SINGLE_SUCCESS
	}

	private fun bulk(source: CommandSourceStack, users: Map<String, GameProfile>?): Int {
		source.ensurePremium("View all the balance of all your accounts at once")
		source.loading("Getting history")
		val embed = EmbedBuilder().setColor(COLOR_INFO)
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved logins. Please perform `.savelogin` before continuing.")).create()
		}
		if (users != null && devices.none { it.accountId in users }) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved accounts that are matching the name(s).")).create()
		}
		forEachSavedAccounts(source, if (users != null) devices.filter { it.accountId in users } else devices) {
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val commonCore = source.api.profileManager.getProfileData("common_core")
			val giftHistory = (commonCore.stats as CommonCoreProfileStats).gift_history
			val within24h = getSentGiftsWithin24H(giftHistory)
			val nit = StringBuilder()
			val gt = if (within24h.isEmpty()) {
				"5 remaining"
			} else {
				within24h.joinTo(nit, "\n") { Date(it.date.time + 24L * 60L * 60L * 1000L).relativeFromNow() }
				"%,d remaining, next%s%s".format(5 - within24h.size, if (within24h.size > 1) ":\n" else " " , nit)
			}
			if (embed.fields.size == 25) {
				source.complete(null, embed.build())
				embed.clearFields()
				source.loading("Getting balances")
			}
			embed.addField(source.api.currentLoggedIn.displayName, gt, true)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun renderUserDate(o: Map.Entry<String, Date>, localUserMap: MutableMap<String, GameProfile>): String {
		val dn = localUserMap[o.key]?.displayName ?: o.key
		return "${o.value.format()}: `$dn`"
	}
}