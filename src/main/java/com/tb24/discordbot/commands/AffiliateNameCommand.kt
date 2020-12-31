package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.L10N
import com.tb24.discordbot.util.*
import com.tb24.fn.model.mcpprofile.attributes.CommonCoreProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commoncore.SetAffiliateName

class AffiliateNameCommand : BrigadierCommand("sac", "Displays or changes the Support a Creator code. Use `clear` to unset the code.", arrayOf("code")) {
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
			val attrs = source.api.profileManager.getProfileData("common_core").stats.attributes as CommonCoreProfileAttributes
			val embed = source.createEmbed()
				.setTitle("Support a Creator")
				.addField("Creator Code", attrs.mtx_affiliate ?: L10N.format("common.none"), false)
				.addField("Set on", attrs.mtx_affiliate_set_time?.format() ?: "Never set", false)
				.setFooter("Use '" + source.prefix + context.commandName + " <new code>' to change it.")
			if (!attrs.mtx_affiliate.isNullOrEmpty() && attrs.mtx_affiliate_set_time != null) {
				val expiry = attrs.mtx_affiliate_set_time.time + 14L * 24L * 60L * 60L * 1000L
				val expired = System.currentTimeMillis() > expiry
				embed.addField("Expires in", if (expired) "**EXPIRED**" else StringUtil.formatElapsedTime(expiry - System.currentTimeMillis(), false).toString(), true)
				if (expired) {
					embed.setColor(0xE53935)
				}
			}
			source.complete(null, embed.build())
		} else {
			val isClear = newCode == "clear"
			source.loading(if (isClear) "Clearing Support a Creator code" else "Applying Support a Creator code")
			source.api.profileManager.dispatchClientCommandRequest(SetAffiliateName().apply {
				affiliateName = if (isClear) "" else newCode
			}).await()
			source.complete(null, source.createEmbed()
				.setDescription(if (isClear) "🗑 **Cleared** Support a Creator code." else "✅ Support a Creator code set to **$newCode**.")
				.setColor(0x4BDA74)
				.build())
		}
		return Command.SINGLE_SUCCESS
	}
}