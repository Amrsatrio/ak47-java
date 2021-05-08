package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.exec
import com.tb24.fn.model.mcpprofile.attributes.CommonCoreProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commoncore.VerifyRealMoneyPurchase

class RedeemPurchasesCommand : BrigadierCommand("redeempurchases", "Redeems Fortnite purchases made using Epic Games Store.", arrayOf("rp")) {
    override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
        .executes { c ->
            val source = c.source
            source.ensureSession()
            source.loading("Getting purchases to redeem")
            source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
            val attrs = source.api.profileManager.getProfileData("common_core").stats.attributes as CommonCoreProfileAttributes
            val iap = attrs.in_app_purchases
            val redeemedReceipts = iap?.receipts ?: emptyArray()
            val ignoredReceipts = iap?.ignoredReceipts ?: emptyArray()
            var attempted = 0
            var success = 0
            for (receipt in source.api.fortniteService.receipts(source.api.currentLoggedIn.id).exec().body()!!) {
                val entry = "EPIC:" + receipt.receiptId
                if (!redeemedReceipts.contains(entry) && !ignoredReceipts.contains(entry)) {
                    ++attempted
                    val payload = VerifyRealMoneyPurchase().apply {
                        appStore = receipt.appStore.name
                        appStoreId = receipt.appStoreId
                        receiptId = receipt.receiptId
                        receiptInfo = receipt.receiptInfo
                    }
                    if (runCatching { source.api.profileManager.dispatchClientCommandRequest(payload).await() }.isSuccess) {
                        ++success
                    }
                }
            }
            if (attempted > 0) {
                source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
                    .setDescription("✅ Redeemed %,d of %,d purchases!".format(success, attempted))
                    .build())
            } else {
                source.complete(null, source.createEmbed().setColor(COLOR_ERROR)
                    .setDescription("❌ You have no purchases to redeem.")
                    .build())
            }
            Command.SINGLE_SUCCESS
        }
}