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
import com.tb24.fn.util.Utils.sumKV
import com.tb24.fn.util.countMtxCurrency
import com.tb24.fn.util.getString
import com.tb24.fn.util.isApplicableOn
import net.dv8tion.jda.api.EmbedBuilder
import java.text.DateFormat
import java.util.regex.Pattern

class MtxBalanceCommand : BrigadierCommand("vbucks", "Shows how much V-Bucks the account owns on all platforms.", arrayOf("bal", "balance", "mtx", "v", "vbucksbalance")) {
	companion object {
		val MTX_FULFILLMENT_PATTERN = Pattern.compile("FN_(\\d+)_POINTS")
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { balance(it.source) }
		.then(literal("totalpurchased").executes { totalPurchased(it.source) })
		.then(literal("bulk").executes { bulk(it.source, null) }.then(argument("bulk users", UserArgument.users(-1)).executes { bulk(it.source, UserArgument.getUsers(it, "bulk users", loadingText = null)) }))

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("balance", description).executes { balance(it) })
		.then(subcommand("totalpurchased", "Shows how much V-Bucks you have purchased.").executes { totalPurchased(it) })

	private fun totalPurchased(source: CommandSourceStack): Int {
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
		return Command.SINGLE_SUCCESS
	}

	private fun balance(source: CommandSourceStack): Int {
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
			.setFooter("V-Bucks platform: " + current + (if (source.hasPremium()) " (" + source.prefix + "vbucksplatform to change)" else ""))
			.setThumbnail(Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Items/T-Items-MTX-L.T-Items-MTX-L"))
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun bulk(source: CommandSourceStack, users: Map<String, GameProfile>?): Int {
		source.ensurePremium("View all the balance of all your accounts at once")
		source.loading("Getting balances")
		val embed = EmbedBuilder().setColor(COLOR_INFO)
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved logins. Please perform `.savelogin` before continuing.")).create()
		}
		var total = 0
		if (users != null && devices.filter { it.accountId in users }.isNullOrEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved accounts that are matching the name(s).")).create()
		}
		forEachSavedAccounts(source, if (users != null) devices.filter { it.accountId in users } else devices) {
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val commonCore = source.api.profileManager.getProfileData("common_core")
			val mtx = countMtxCurrency(commonCore)
			if (embed.fields.size == 25) {
				source.complete(null, embed.build())
				embed.clearFields()
				source.loading("Getting balances")
			}
			embed.addField(source.api.currentLoggedIn.displayName, "${Utils.MTX_EMOJI} ${Formatters.num.format(mtx)}", true)
			total += mtx
		}
		embed.setFooter("Total: %,d V-Bucks".format(total))
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}