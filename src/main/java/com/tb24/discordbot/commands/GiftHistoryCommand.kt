package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.attributes.CommonCoreProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.CatalogHelper
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.MessageBuilder
import java.util.*

class GiftHistoryCommand : BrigadierCommand("gifthistory", "Displays how much gifting slots you have left along with a partial history of sent/received gifts.", arrayOf("gh")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting gift history")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val commonCore = source.api.profileManager.getProfileData("common_core")
			val giftHistory = (commonCore.stats.attributes as CommonCoreProfileAttributes).gift_history
			val sentTo = giftHistory?.sentTo ?: emptyMap()
			val receivedFrom = giftHistory?.receivedFrom ?: emptyMap()
			val gifts = giftHistory?.gifts ?: emptyArray()
			val localUserMap = mutableMapOf<String, GameProfile>()
			val idsToQuery = mutableSetOf<String>()
			idsToQuery.addAll(sentTo.keys)
			idsToQuery.addAll(receivedFrom.keys)
			gifts.forEach { idsToQuery.add(it.toAccountId) }
			if (idsToQuery.size > 0) {
				source.queryUsers(idsToQuery).forEach { localUserMap[it.id] = it }
			}
			source.client.catalogManager.ensureCatalogData(source.api)
			val within24h = CatalogHelper.getSentGiftsWithin24H(giftHistory)
			val embed = source.createEmbed()
				.setTitle("Gift History")
				.setDescription("${
					if (within24h.size < 5) {
						"**${Formatters.num.format(5 - within24h.size)}** daily gifts remaining."
					} else {
						val date = Date(within24h.first().date.time + 24L * 60L * 60L * 1000L)
						"Daily gifts limit reached. You'll be able to gift again in ${StringUtil.formatElapsedTime(date.time - System.currentTimeMillis(), false)} (${date.format()})."
					}
				}\nTotal sent: **${giftHistory.num_sent}**\nTotal received: **${giftHistory.num_received}**\nThe data below are partial.")
				.setFooter("Server time: ${Date().format()}")
			embed.addField("Sent (${Formatters.num.format(sentTo.size)})", summary(sentTo, localUserMap, source.prefix + c.commandName + " sent"), false)
			embed.addField("Received (${Formatters.num.format(receivedFrom.size)})", summary(receivedFrom, localUserMap, source.prefix + c.commandName + " received"), false)
			embed.addFieldSeparate("Recent gifts (${Formatters.num.format(gifts.size)})", gifts.toList()) { e ->
				val catalogEntry = source.client.catalogManager.purchasableCatalogEntries.firstOrNull { it.offerId == e.offerId }
				"${catalogEntry?.holder()?.friendlyName ?: "<Item outside of current shop>"} to ${localUserMap[e.toAccountId]?.displayName ?: e.toAccountId} on ${e.date.format()}"
			}
			source.complete(null, embed.build())
			Command.SINGLE_SUCCESS
		}
		.then(literal("sent")
			.executes { detail(it.source, false) }
		)
		.then(literal("received")
			.executes { detail(it.source, true) }
		)

	private fun summary(map: Map<String, Date>, localUserMap: MutableMap<String, GameProfile>, commandHint: String, limit: Int = 10): String {
		val lines = mutableListOf<String>()
		var i = 0
		for (o in map) {
			lines.add(renderUserDate(o, localUserMap))
			if (++i == limit) {
				lines.add("... ${Formatters.num.format(map.size - limit)} more, `$commandHint` to show more")
				break
			}
		}
		return lines.joinToString("\n")
	}

	private fun detail(source: CommandSourceStack, isReceive: Boolean): Int {
		source.ensureSession()
		source.loading("Getting gift history")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
		val commonCore = source.api.profileManager.getProfileData("common_core")
		val giftHistory = (commonCore.stats.attributes as CommonCoreProfileAttributes).gift_history
		val data = (if (isReceive) giftHistory?.receivedFrom else giftHistory?.sentTo) ?: emptyMap()
		val localUserMap = mutableMapOf<String, GameProfile>()
		val idsToQuery = mutableSetOf<String>()
		idsToQuery.addAll(data.keys)
		if (idsToQuery.size > 0) {
			source.queryUsers(idsToQuery).forEach { localUserMap[it.id] = it }
		}
		source.message.replyPaginated(data.entries.sortedBy { it.value }, 20, source.loadingMsg) { content, page, pageCount ->
			MessageBuilder(source.createEmbed()
				.setTitle("Gift History / ${if (isReceive) "Received" else "Sent"}")
				.setDescription("Showing ${page * 10 + 1} to ${page * 10 + content.size} of ${data.size} entries" + "\n\n" + content.sortedBy { it.value }.joinToString("\n") { "\u00b7 ${renderUserDate(it, localUserMap)}" })
				.setFooter("Page %,d of %,d".format(page + 1, pageCount))
			).build()
		}
		return Command.SINGLE_SUCCESS
	}

	private fun renderUserDate(o: Map.Entry<String, Date>, localUserMap: MutableMap<String, GameProfile>): String {
		//val dn = localUserMap[o.key]?.displayName?.replace("*", "\\*")?.replace("_", "\\_")?.replace("~", "\\~") ?: o.key
		val dn = localUserMap[o.key]?.displayName ?: o.key
		return "${o.value.format()}: `$dn`"
	}
}