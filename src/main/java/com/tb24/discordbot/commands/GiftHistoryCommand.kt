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
import java.util.*

class GiftHistoryCommand : BrigadierCommand("gifthistory", "Displays how much gifting slots you have left along with a partial history of sent/received gifts.", arrayListOf("gifth")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting your gift history")
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
				.setColor(0x40FAA1)
			val fn: (Map.Entry<String, Date>) -> String = { o ->
//				val dn = localUserMap[o.key]?.displayName?.replace("*", "\\*")?.replace("_", "\\_")?.replace("~", "\\~") ?: o.key
				val dn = localUserMap[o.key]?.displayName ?: o.key
				"`$dn` on ${o.value.format()}"
			}
			embed.addFieldSeparate("Sent to", sentTo.entries.sortedBy { it.value }, mapper = fn)
			embed.addFieldSeparate("Received from", receivedFrom.entries.sortedBy { it.value }, mapper = fn)
			embed.addFieldSeparate("Recent gifts", gifts.toList()) { e ->
				val catalogEntry = source.client.catalogManager.purchasableCatalogEntries.firstOrNull { it.offerId == e.offerId }
				"${catalogEntry?.holder()?.friendlyName ?: "<Item outside of current shop>"} to ${localUserMap[e.toAccountId]?.displayName ?: e.toAccountId} on ${e.date.format()}"
			}
			source.complete(null, embed.build())
			Command.SINGLE_SUCCESS
		}
}