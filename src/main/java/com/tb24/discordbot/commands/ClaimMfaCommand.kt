package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.BoolArgumentType.getBool
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.L10N
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commoncore.ClaimMfaEnabled
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import java.util.concurrent.CompletableFuture

class ClaimMfaCommand : BrigadierCommand("claimmfa", "Claim 2FA reward (Boogie Down emote for BR) on your account.", arrayOf("claim2fa", "boogiedown")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes(::execute)
		.then(argument("claim for STW", bool())
			.executes { execute(it, getBool(it, "claim for STW")) }
		)

	private fun execute(context: CommandContext<CommandSourceStack>, claimForStw: Boolean = false): Int {
		val source = context.source
		source.ensureSession()
		source.errorTitle = "Failed to claim 2FA reward"
		source.loading(L10N.format("generic.loading"))
		val subGameName = if (claimForStw) "Save the World" else "Battle Royale"
		val profileId = if (claimForStw) "campaign" else "athena"
		CompletableFuture.allOf(
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()),
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId)
		).await()
		val stats = source.api.profileManager.getProfileData(profileId).stats
		val claimed = if (claimForStw) {
			if (source.api.profileManager.getProfileData("common_core").items.values.none { it.templateId == "Token:campaignaccess" }) {
				throw SimpleCommandExceptionType(LiteralMessage("You don't have access to Save the World.")).create()
			}
			(stats as CampaignProfileStats).mfa_reward_claimed
		} else {
			(stats as AthenaProfileStats).mfa_reward_claimed
		}
		if (claimed) {
			throw SimpleCommandExceptionType(LiteralMessage("You have already claimed the 2FA reward for $subGameName, so no need to claim again.")).create()
		}
		source.loading("Claiming 2FA reward")
		val response = source.api.profileManager.dispatchClientCommandRequest(ClaimMfaEnabled().apply { bClaimForStw = claimForStw }).await()
		if (response.profileRevision > response.profileChangesBaseRevision) {
			source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
				.setTitle("✅ 2FA reward claimed for $subGameName")
				.setDescription("${if (claimForStw) "" else "You're now ready to send gifts and participate in tournaments.\n"}It's your choice whether you want to keep it enabled or disable it for your convenience, albeit making the account less secure.")
				.build())
		} else {
			source.complete(null, source.createEmbed().setColor(COLOR_ERROR)
				.setTitle("❌ Failed to claim 2FA reward")
				.setDescription("Please make sure that you already have two-factor authentication enabled on your account prior to attempting to claim the rewards.\n\n[Enable two-factor authentication on epicgames.com](${source.generateUrl("https://www.epicgames.com/account/password#2fa-signup")})")
				.build())
		}
		return Command.SINGLE_SUCCESS
	}
}