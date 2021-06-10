package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.Rune
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.addFieldSeparate
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.Utils.sumKV
import com.tb24.fn.util.countMtxCurrency
import com.tb24.fn.util.getString
import com.tb24.fn.util.isApplicableOn
import java.text.DateFormat
import java.util.regex.Pattern

class MtxBalanceCommand : BrigadierCommand("vbucks", "Shows how much V-Bucks the account owns on all platforms.", arrayOf("bal", "balance", "mtx", "v", "vbucksbalance")) {
	companion object {
		val MTX_FULFILLMENT_PATTERN = Pattern.compile("FN_(\\d+)_POINTS")
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Getting balance")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val commonCore = source.api.profileManager.getProfileData("common_core")
			val current = (commonCore.stats as CommonCoreProfileStats).current_mtx_platform
			val breakdown = mutableListOf<String>()
			var i = 0
			for (item in commonCore.items.values) {
				if (item.primaryAssetType == "Currency") {
					val platform = item.attributes.getString("platform", "Shared")
					val entryStr = String.format("%,d. %,d \u00d7 %s %s", ++i, item.quantity, platform, item.templateId.replace("Currency:Mtx", ""))
					breakdown.add(if (current.isApplicableOn(platform)) "**$entryStr**" else entryStr)
				}
			}
			source.complete(null, source.createEmbed()
				.setTitle(Formatters.num.format(countMtxCurrency(commonCore)) + " V-Bucks")
				.addField("Breakdown", if (breakdown.isEmpty()) "You have no V-Bucks." else breakdown.joinToString("\n"), false)
				.setFooter("V-Bucks platform: " + current + (if (Rune.hasPremium(source)) " (" + source.prefix + "vbucksplatform to change)" else ""))
				.setThumbnail(Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Items/T-Items-MTX-L.T-Items-MTX-L"))
				.build())
			Command.SINGLE_SUCCESS
		}
		.then(literal("totalpurchased")
			.executes { c ->
				val source = c.source
				source.ensureSession()
				source.loading("Getting fulfillments")
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
				val commonCore = source.api.profileManager.getProfileData("common_core")
				val fulfillments = (commonCore.stats as CommonCoreProfileStats).in_app_purchases?.fulfillmentCounts
				var total = 0
				val entries = sortedMapOf<Int, Int>()
				fulfillments?.forEach { (name, count) ->
					val matcher = MTX_FULFILLMENT_PATTERN.matcher(name)
					if (matcher.matches()) {
						val mtxAmount = matcher.group(1).toInt()
						total += count * mtxAmount
						sumKV(entries, mtxAmount, count)
					}
				}
				source.complete(null, source.createEmbed()
					.setDescription("You have purchased/redeemed a total of **%s %,d** since **%s**.".format(Utils.MTX_EMOJI, total, DateFormat.getDateInstance().format(commonCore.created)))
					.addFieldSeparate("Details", entries.entries, 0) { "%s %,d: %,d %s".format(Utils.MTX_EMOJI, it.key, it.value, if (it.value == 1) "time" else "times") }
					.build())
				Command.SINGLE_SUCCESS
			}
		)
}