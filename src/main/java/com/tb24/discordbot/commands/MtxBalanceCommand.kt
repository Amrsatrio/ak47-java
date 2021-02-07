package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.attributes.CommonCoreProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.CatalogHelper
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.getStringOr

class MtxBalanceCommand : BrigadierCommand("vbucks", "Shows how much V-Bucks the account owns on all platforms.", arrayOf("bal", "balance", "mtx", "v", "vbucksbalance")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Getting balance")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val commonCore = source.api.profileManager.getProfileData("common_core")
			val current = (commonCore.stats.attributes as CommonCoreProfileAttributes).current_mtx_platform
			val breakdown = mutableListOf<String>()
			var i = 0
			for (item in commonCore.items.values) {
				if (item.primaryAssetType == "Currency") {
					val platform = item.attributes.getStringOr("platform", "Shared")
					val entryStr = String.format("%,d. %,d \u00d7 %s %s", ++i, item.quantity, platform, item.templateId.replace("Currency:Mtx", ""))
					breakdown.add(if (CatalogHelper.applicable(current, platform)) "**$entryStr**" else entryStr)
				}
			}
			source.complete(null, source.createEmbed()
				.setTitle(Formatters.num.format(CatalogHelper.countMtxCurrency(commonCore)) + " V-Bucks")
				.addField("Breakdown", if (breakdown.isEmpty()) "You have no V-Bucks." else breakdown.joinToString("\n"), false)
				.setFooter("V-Bucks platform: " + current + " (" + source.prefix + "vbucksplatform to change)")
				.setThumbnail(Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Items/T-Items-MTX-L.T-Items-MTX-L"))
				.build())
			Command.SINGLE_SUCCESS
		}
}