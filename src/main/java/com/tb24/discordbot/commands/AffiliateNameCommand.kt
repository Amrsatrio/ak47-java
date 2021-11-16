package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.L10N
import com.tb24.discordbot.util.*
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commoncore.SetAffiliateName
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats

class AffiliateNameCommand : BrigadierCommand("sac", "Displays or changes the Support a Creator code.", arrayOf("code")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it, null) }
		.then(argument("new code", greedyString())
			.executes { execute(it, getString(it, "new code")) }
		)

	private fun execute(context: CommandContext<CommandSourceStack>, newCode: String?): Int {
		val source = context.source
		source.ensureSession()
		if (newCode == null) {
			source.loading("Getting current Support a Creator code")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val stats = source.api.profileManager.getProfileData("common_core").stats as CommonCoreProfileStats
			val embed = source.createEmbed()
				.setTitle("Support a Creator")
				.addField("Creator Code", stats.mtx_affiliate ?: L10N.format("common.none"), false)
				.addField("Set on", stats.mtx_affiliate_set_time?.format() ?: "Never set", false)
				.setFooter("Use '" + source.prefix + context.commandName + " <new code>' to change it.")
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
			source.loading("Applying Support a Creator code")
			source.api.profileManager.dispatchClientCommandRequest(SetAffiliateName().apply {
				affiliateName = newCode
			}).await()
			source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
				.setDescription("✅ Support a Creator code set to **$newCode**.")
				.build())
		}
		return Command.SINGLE_SUCCESS
	}
}
