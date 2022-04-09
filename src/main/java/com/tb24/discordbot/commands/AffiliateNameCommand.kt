package com.tb24.discordbot.commands

import com.google.gson.JsonObject
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.L10N
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commoncore.SetAffiliateName
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.network.AccountService
import com.tb24.fn.util.EAuthClient
import com.tb24.fn.util.getString
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import okhttp3.OkHttpClient
import java.text.NumberFormat
import java.util.*

class AffiliateNameCommand : BrigadierCommand("sac", "Displays or changes the Support-a-Creator code.", arrayOf("code")) {
	companion object {
		const val MIN_PAYOUT_ELIGIBILITY = 100.0
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source, null) }
		.then(argument("new code", greedyString())
			.executes { execute(it.source, getString(it, "new code")) }
		)
		.then(literal("earnings").executes { earnings(it.source) })
		.then(literal("bulk").then(argument("code", greedyString())
			.executes { bulk(it.source, getString(it, "code")) }
		))

	override fun getSlashCommand() = newCommandBuilder()
		.option(OptionType.STRING, "new-code", "The new Support-a-Creator code to apply.")
		.executes { execute(it, it.getOption("new-code")?.asString) }

	private fun execute(source: CommandSourceStack, newCode: String?): Int {
		source.ensureSession()
		if (newCode == null) {
			source.loading("Getting current Support-a-Creator code")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val stats = source.api.profileManager.getProfileData("common_core").stats as CommonCoreProfileStats
			val embed = source.createEmbed()
				.setTitle("Support-a-Creator")
				.addField("Creator Code", stats.mtx_affiliate ?: L10N.format("common.none"), false)
				.addField("Set on", stats.mtx_affiliate_set_time?.format() ?: "Never set", false)
				.setFooter("Use '" + source.prefix + source.commandName + " <new code>' to change it.")
			if (!stats.mtx_affiliate.isNullOrEmpty() && stats.mtx_affiliate_set_time != null) {
				val expiry = stats.mtx_affiliate_set_time.time + 14L * 24L * 60L * 60L * 1000L
				val expired = System.currentTimeMillis() > expiry
				embed.addField("Expires", if (expired) "**EXPIRED**" else expiry.relativeFromNow(), true)
				if (expired) {
					embed.setColor(0xE53935)
				}
			}
			source.complete(null, embed.build())
		} else {
			source.loading("Applying Support-a-Creator code")
			source.api.profileManager.dispatchClientCommandRequest(SetAffiliateName().apply {
				affiliateName = newCode
			}).await()
			source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
				.setDescription("✅ Support-a-Creator code set to **$newCode**.")
				.build())
		}
		return Command.SINGLE_SUCCESS
	}

	private fun bulk(source: CommandSourceStack, newCode: String): Int {
		source.loading("Applying Support-a-Creator code")
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved logins. Please perform `.savelogin` before continuing.")).create()
		}
		val api = EpicApi(OkHttpClient())
		val token = api.accountService.getAccessToken(EAuthClient.FORTNITE_PC_GAME_CLIENT.asBasicAuthString(), AccountService.GrantType.clientCredentials(), null, null).exec().body()
		api.setToken(token)
		api.affiliateService.checkAffiliateSlug(newCode).exec().body()!!
		api.accountService.killSession(api.userToken.access_token).exec()
		var i = 0
		val embed = EmbedBuilder()
		forEachSavedAccounts(source, devices) {
			source.api.profileManager.dispatchClientCommandRequest(SetAffiliateName().apply {
				affiliateName = newCode
			}).await()
			i++
		}
		embed.setColor(COLOR_SUCCESS)
		embed.setDescription("✅ Applied **$newCode** on **$i/${devices.size}** accounts.")
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun earnings(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting Support-a-Creator earnings")
		val data = source.api.performWebApiRequest<JsonObject>("https://www.epicgames.com/affiliate/api/v2/get-earnings-data?version=2").getAsJsonObject("data")
		val priceFormatter = NumberFormat.getCurrencyInstance()
		priceFormatter.currency = Currency.getInstance(data.getString("lifetimePayoutsCurrency"))
		val eligibleEarnings = data.get("eligibleEarnings").asDouble
		val eligibleForPayout = eligibleEarnings >= MIN_PAYOUT_ELIGIBILITY
		//val lastPayoutDate = if (data.getString("lastPayoutDate") != null) data.getDateISO("lastPayoutDate") else null TODO date formats returned by the API are broken
		val embed = source.createEmbed()
			.addField("Unpaid", if (eligibleForPayout) priceFormatter.format(eligibleEarnings) else "`%s`\n%s / %s".format(Utils.progress(eligibleEarnings.toFloat(), MIN_PAYOUT_ELIGIBILITY.toFloat(), 32), priceFormatter.format(eligibleEarnings), priceFormatter.format(MIN_PAYOUT_ELIGIBILITY)), false)
			.addField("Last payment"/*.format(lastPayoutDate?.let { TimeFormat.DATE_TIME_SHORT.format(it.toInstant()) } ?: "N/A")*/, priceFormatter.format(data.get("lastPayout").asDouble), true)
			.addField("Lifetime", priceFormatter.format(data.get("lifetimePayouts").asDouble), true)
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}