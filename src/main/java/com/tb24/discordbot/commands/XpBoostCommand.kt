package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.ActivateConsumable

class XpBoostCommand : BrigadierCommand("xpboost", "Applies an XP boost to an account.", arrayOf("xpb", "xb")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source, 1, mutableListOf(it.source.api.currentLoggedIn)) }
		.then(argument("quantity", IntegerArgumentType.integer())
			.executes { execute(it.source, IntegerArgumentType.getInteger(it, "quantity"), mutableListOf(it.source.api.currentLoggedIn)) }
			.then(argument("users", UserArgument.users(100))
				.executes { execute(it.source, IntegerArgumentType.getInteger(it, "quantity"), UserArgument.getUsers(it, "users").values) }
			)
		)

	private fun execute(source: CommandSourceStack, quantity: Int = 1, users: Collection<GameProfile>): Int {
		source.ensureSession()
		val profileManager = source.session.api.profileManager
		profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = profileManager.getProfileData("campaign")
		source.loading("Applying boosts")
		val boosts = campaign.items.values.filter { it.templateId.startsWith("ConsumableAccountItem", true) }
		val applied = mutableListOf<Pair<String, Int>>()
		for (account in users) {
			val boost = (if (account.id == source.api.currentLoggedIn.id) boosts.firstOrNull { it.templateId.equals("ConsumableAccountItem:smallxpboost", true) } else boosts.firstOrNull { it.templateId.equals("ConsumableAccountItem:smallxpboost_gift", true) })
				?: throw SimpleCommandExceptionType(LiteralMessage("No XP Boosts")).create()
			if (boost.quantity < quantity) throw SimpleCommandExceptionType(LiteralMessage("Not enough XP Boosts (%,d/%,d)".format(boost.quantity, quantity))).create()
			var numApplied = 0
			for (i in 1..quantity) {
				source.api.profileManager.dispatchClientCommandRequest(ActivateConsumable().apply {
					targetItemId = boost.itemId
					targetAccountId = account.id
				}, "campaign").await()
				numApplied++
			}
			applied.add(account.displayName to numApplied)
		}
		source.complete(null, source.createEmbed().addField("✅ Applied XP Boosts", applied.joinToString("\n") { "**${it.first}**: ${it.second}" }, false).build())
		return Command.SINGLE_SUCCESS
	}
}