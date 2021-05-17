package com.tb24.discordbot.commands

import com.google.gson.internal.bind.util.ISO8601Utils
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.L10N
import com.tb24.discordbot.util.*
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commoncore.RefundMtxPurchase
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.util.CatalogHelper
import com.tb24.fn.util.Formatters
import java.text.ParsePosition

class UndoCommand : BrigadierCommand("undo", "Cancel your last purchase.", arrayOf("cancelpurchase", "refund")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Finding your last purchase")
			val profileManager = source.api.profileManager
			profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val commonCore = profileManager.getProfileData("common_core")
			val attrs = commonCore.stats as CommonCoreProfileStats
			val purchase = attrs.mtx_purchase_history?.purchases?.lastOrNull()
			if (purchase == null || attrs.undo_timeout == "min") {
				throw SimpleCommandExceptionType(LiteralMessage(L10N.format("undo.failed.nothing_to_undo"))).create()
			}
			source.client.catalogManager.ensureCatalogData(source.api)
			val catalogEntry = source.client.catalogManager.purchasableCatalogEntries.firstOrNull { it.offerId == purchase.offerId }
			val catalogEntryName = catalogEntry?.holder()?.friendlyName ?: "<Item outside of current shop>"
			if (System.currentTimeMillis() >= ISO8601Utils.parse(attrs.undo_timeout, ParsePosition(0)).time) {
				throw SimpleCommandExceptionType(LiteralMessage(L10N.format("undo.failed.expired", catalogEntryName))).create()
			}
			val undoCooldown = CatalogHelper.getUndoCooldown(commonCore, purchase.offerId)
			if (undoCooldown != null && System.currentTimeMillis() < undoCooldown.cooldownExpires.time) {
				throw SimpleCommandExceptionType(LiteralMessage(L10N.format("undo.failed.cooldown", catalogEntryName, StringUtil.formatElapsedTime(undoCooldown.cooldownExpires.time - System.currentTimeMillis(), false)))).create()
			}
			val embed = source.createEmbed()
				.setColor(0x00FF00)
				.setTitle(L10N.format("undo.confirmation.title"))
				.setDescription(L10N.format("undo.confirmation.disclaimer"))
				.addField(L10N.format("undo.confirmation.purchase_id"), "`" + purchase.purchaseId + "`", false)
				.addField(L10N.format("undo.confirmation.purchase_date"), purchase.purchaseDate.format(), false)
				.addField(L10N.format("undo.confirmation.free_refund_eligible"), if (purchase.freeRefundEligible) L10N.format("common.yes") else L10N.format("common.no"), false)
				.addField(L10N.format("catalog.items"), purchase.lootResult.run { if (isEmpty()) "No items" else joinToString("\n") { it.asItemStack().render() } }, false)
				.addField(L10N.format("undo.confirmation.total_mtx_paid"), Utils.MTX_EMOJI + " " + Formatters.num.format(purchase.totalMtxPaid), false)
				.addField(L10N.format("sac.verb"), purchase.metadata?.get("mtx_affiliate")?.asString ?: L10N.format("common.none"), false)
			if (source.complete(null, embed.build()).yesNoReactions(source.author).await()) {
				source.errorTitle = "Cancel Purchase Failed"
				source.loading("Cancelling purchase of $catalogEntryName")
				profileManager.dispatchClientCommandRequest(RefundMtxPurchase().apply {
					purchaseId = purchase.purchaseId
					quickReturn = true
				}).await()
				source.complete("✅ Purchase canceled. Now you have ${Utils.MTX_EMOJI} ${Formatters.num.format(CatalogHelper.countMtxCurrency(profileManager.getProfileData("common_core")))}.")
				Command.SINGLE_SUCCESS
			} else {
				throw SimpleCommandExceptionType(LiteralMessage("Purchase cancellation canceled.")).create()
			}
		}
}