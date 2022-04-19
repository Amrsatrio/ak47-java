package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.EFortMtxPlatform
import com.tb24.fn.model.mcpprofile.commands.commoncore.SetMtxPlatform
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.countMtxCurrency

class MtxPlatformCommand : BrigadierCommand("vbucksplatform", "Changes the V-Bucks platform.", arrayOf("vp", "mtxplatform")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("platform", word())
			.suggests { _, b -> EFortMtxPlatform.values().fold(b) { acc, it -> acc.suggest(it.name) }.buildFuture() }
			.executes { c ->
				val source = c.source
				source.ensureSession()
				val platformArg = getString(c, "platform")
				val platform = EFortMtxPlatform.values().firstOrNull { it.name.equals(platformArg, true) }
					?: throw SimpleCommandExceptionType(LiteralMessage("Invalid platform `$platformArg`. Valid platforms are:```\n${EFortMtxPlatform.values().joinToString()}```")).create()
				source.loading("Changing V-Bucks platform")
				source.api.profileManager.dispatchClientCommandRequest(SetMtxPlatform().apply { newPlatform = platform }).await()
				val commonCore = source.api.profileManager.getProfileData("common_core")
				val stats = commonCore.stats as CommonCoreProfileStats
				source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
					.setTitle("✅ Updated V-Bucks platform to **${stats.current_mtx_platform}**.")
					.setDescription("Balance: ${Formatters.num.format(countMtxCurrency(commonCore))} V-Bucks on this platform.\nUse `${source.prefix}vbucks` to see all of your V-Bucks.")
					.build())
				Command.SINGLE_SUCCESS
			}
		)
}