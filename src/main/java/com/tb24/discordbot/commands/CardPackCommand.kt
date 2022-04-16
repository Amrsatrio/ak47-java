package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpLootEntry
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.PopulatePrerolledOffers
import com.tb24.fn.util.getString
import net.dv8tion.jda.api.MessageBuilder
import java.util.concurrent.CompletableFuture

class CardPackCommand : BrigadierCommand("llamas", "Look at your llamas and open them.", arrayOf("ll","ocp")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("X-raying llamas")
		val profileManager = source.api.profileManager
		CompletableFuture.allOf(
			profileManager.dispatchClientCommandRequest(PopulatePrerolledOffers(), "campaign"),
			profileManager.dispatchClientCommandRequest(QueryProfile(), "common_core")
		).await()
		val campaign = profileManager.getProfileData("campaign")
		val prerolls = campaign.items.values.filter { it.primaryAssetType == "PrerollData" }
		if (prerolls.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You have no prerolls.")).create()
		}
		val catalogManager = source.client.catalogManager
		catalogManager.ensureCatalogData(source.client.internalSession.api)
		val section = catalogManager.catalogData?.storefronts?.find { it.name == "CardPackStorePreroll" }
			?: throw SimpleCommandExceptionType(LiteralMessage("Could not find preroll section.")).create()
		val llamas = mutableListOf<Pair<String, List<FortItemStack>>>()
		for (offer in section.catalogEntries) {
			val prerolledOffer = prerolls.firstOrNull { it.attributes.getString("offerId") == offer.offerId } ?: continue
			val sd = offer.holder().apply { resolve(profileManager) }
			val canNotPurchase = (sd.owned || sd.purchaseLimit >= 0 && sd.purchasesCount >= sd.purchaseLimit || offer.prices.all { it.finalPrice > it.getAccountBalance(profileManager)})
			if (canNotPurchase) {
				continue
			}
			val items = EpicApi.GSON.fromJson(prerolledOffer.attributes.getAsJsonArray("items"), Array<McpLootEntry>::class.java)
				.map { it.asItemStack() }
				.sortedWith { a, b ->
					val aRarity = a.rarity
					val bRarity = b.rarity
					if (aRarity != bRarity) {
						bRarity.compareTo(aRarity)
					} else {
						val aRating = a.powerLevel
						val bRating = b.powerLevel
						if (aRating != bRating) {
							bRating.compareTo(aRating)
						} else {
							a.templateId.compareTo(b.templateId)
						}
					}
				}
			llamas.add(sd.friendlyName to items)
		}
		source.replyPaginated(llamas, 1) { content, page, pageCount ->
			val (name, items) = content.first()
			val embed = source.createEmbed()
				.setTitle(name)
				//.setDescription("Balance: TODO")
				.addFieldSeparate("Contents", items, 0, true) { it.render(useDefaultRarityEmote = items.size > 10) }
				.setFooter("%,d of %,d".format(page + 1, pageCount))
			MessageBuilder(embed.build())
		}
		return Command.SINGLE_SUCCESS
	}
}