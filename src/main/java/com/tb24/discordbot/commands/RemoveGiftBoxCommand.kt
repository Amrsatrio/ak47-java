package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.Session
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.forEachSavedAccounts
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.RemoveGiftBox
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin
import net.dv8tion.jda.api.EmbedBuilder
import java.util.concurrent.CompletableFuture

class RemoveGiftBoxCommand : BrigadierCommand("removegiftbox", "Checks for new gift boxes and removes them.", arrayOf("rg")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(literal("bulk")
			.executes { bulk(it.source) }
		)

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Removing gift boxes")
		val removed = removeGiftBoxes(source.session)
		if (removed != 0) {
			source.complete(null, source.createEmbed().setDescription("✅ Removed **%,d** gift box%s.".format(removed, if (removed == 1) "" else "es")).build())
		} else {
			throw SimpleCommandExceptionType(LiteralMessage("No gift boxes found")).create()
		}
		return Command.SINGLE_SUCCESS
	}

	private fun bulk(source: CommandSourceStack): Int {
		val devices = source.client.savedLoginsManager.getAll(source.author.id)
		if (devices.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have saved logins. Please perform `.savelogin` before continuing.")).create()
		}
		source.loading("Removing gift boxes")
		val embed = EmbedBuilder().setColor(COLOR_INFO)
		forEachSavedAccounts(source, devices) {
			if (embed.fields.size == 25) {
				source.complete(null, embed.build())
				embed.clearFields()
				source.loading("Removing gift boxes")
			}
			val removed = removeGiftBoxes(it)
			if (removed > 0) {
				embed.addField(it.api.currentLoggedIn.displayName, "✅ Removed **%,d** gift box%s.".format(removed, if (removed == 1) "" else "es"), false)
			}
		}
		if (embed.fields.isNotEmpty()) {
			source.complete(null, embed.build())
		} else {
			throw SimpleCommandExceptionType(LiteralMessage("No gift boxes found")).create()
		}
		return Command.SINGLE_SUCCESS
	}

	private fun removeGiftBoxes(session: Session): Int {
		CompletableFuture.allOf(
			session.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "athena"),
			session.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "campaign"),
			session.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "common_core")
		).await()
		val athena = session.api.profileManager.getProfileData("athena")
		val campaign = session.api.profileManager.getProfileData("campaign")
		val commonCore = session.api.profileManager.getProfileData("common_core")
		val agb = athena.items.values.filter { it.templateId.startsWith("GiftBox:", true) }
		val cgb = campaign.items.values.filter { it.templateId.startsWith("GiftBox:", true) }
		val ccgb = commonCore.items.values.filter { it.templateId.startsWith("GiftBox:", true) }
		if (agb.isNotEmpty()) {
			session.api.profileManager.dispatchClientCommandRequest(RemoveGiftBox().apply {
				giftBoxItemIds = agb.map { it.itemId }.toTypedArray()
			}, "athena")
		}
		if (cgb.isNotEmpty()) {
			session.api.profileManager.dispatchClientCommandRequest(RemoveGiftBox().apply {
				giftBoxItemIds = cgb.map { it.itemId }.toTypedArray()
			}, "campaign")
		}
		if (ccgb.isNotEmpty()) {
			session.api.profileManager.dispatchClientCommandRequest(RemoveGiftBox().apply {
				giftBoxItemIds = ccgb.map { it.itemId }.toTypedArray()
			}, "campaign")
		}
		return agb.size + cgb.size + ccgb.size
	}
}