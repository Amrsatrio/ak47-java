package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats.MtxPurchaseHistoryEntry
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.utils.TimeFormat
import java.util.*

class PurchasesCommand : BrigadierCommand("purchases", "Shows your purchase history of V-Bucks priced items.", arrayOf("purchasehistory")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting purchase history")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val commonCore = source.api.profileManager.getProfileData("common_core")
			val mtxPurchaseHistory = (commonCore.stats as CommonCoreProfileStats).mtx_purchase_history
			val entries = mtxPurchaseHistory?.purchases
			if (entries.isNullOrEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage("You haven't made a V-Bucks transaction recently.")).create()
			}
			var totalSpent = 0
			var oldest = Long.MAX_VALUE
			for (it in entries) {
				if (it.refundDate != null) {
					continue
				}
				totalSpent += it.totalMtxPaid
				val l = it.purchaseDate.time
				if (l < oldest) {
					oldest = l
				}
			}
			source.message.replyPaginated(entries.sortedByDescending { it.purchaseDate }, 5, source.loadingMsg) { content, page, pageCount ->
				val entriesStart = page * 5 + 1
				val entriesEnd = entriesStart + content.size
				val value = content.joinToString("\n\n") { it.render() }
				val embed = source.createEmbed()
					.setTitle("Purchases")
					.setDescription("Showing %,d to %,d of %,d entries\n\n%s".format(entriesStart, entriesEnd - 1, entries.size, value))
					.addField("Info", "Spent since %s: %s %,d\nRefund tickets: %,d / %,d\nItems refunded: %,d".format(TimeFormat.DATE_LONG.format(oldest), Utils.MTX_EMOJI, totalSpent, mtxPurchaseHistory.refundCredits, 3, mtxPurchaseHistory.refundsUsed), false)
					.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				MessageBuilder(embed).build()
			}
			Command.SINGLE_SUCCESS
		}

	private fun MtxPurchaseHistoryEntry.render(): String {
		val sb = StringBuilder()
		lootResult.joinTo(sb, prefix = "**", postfix = "**") { it.asItemStack().render() }
		sb.append((if (refundDate != null) " \u2014 %s ~~%,d~~" else " \u2014 %s %,d").format(Utils.MTX_EMOJI, totalMtxPaid))
		sb.append("\nPurchased: " + purchaseDate.format())
		refundDate?.let {
			sb.append("\nRefunded: " + it.format())
		}
		metadata?.get("mtx_affiliate")?.let {
			sb.append("\nCreator supported: " + it.asString)
		}
		return sb.toString()
	}
}