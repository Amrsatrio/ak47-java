package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.item.ItemUtils
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.ProfileManager
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.CatalogOffer.CatalogItemPrice
import com.tb24.fn.model.mcpprofile.McpLootEntry
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.PopulatePrerolledOffers
import com.tb24.fn.util.getString
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.ComponentInteraction
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import java.util.concurrent.CompletableFuture

class CardPackCommand : BrigadierCommand("llamas", "Look at your llamas and open them.", arrayOf("ll", "ocp")) {
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
		val llamas = mutableListOf<Entry>()
		for (offer in section.catalogEntries) {
			val prerollData = prerolls.firstOrNull { it.attributes.getString("offerId") == offer.offerId } ?: continue
			val sd = offer.holder().apply { resolve(profileManager) }
			val canNotPurchase = (sd.owned || sd.purchaseLimit >= 0 && sd.purchasesCount >= sd.purchaseLimit)
			if (canNotPurchase) {
				continue
			}
			val linkedPrerollOffer = offer.getMeta("LinkedPrerollOffer")?.substringAfter("OfferId:")
			if (linkedPrerollOffer != null) {
				val existing = llamas.firstOrNull { llama -> llama.offers.any { it.ce.offerId == linkedPrerollOffer } }
				if (existing != null) {
					existing.addOffer(sd)
					continue
				}
			}
			val items = EpicApi.GSON.fromJson(prerollData.attributes.getAsJsonArray("items"), Array<McpLootEntry>::class.java)
				.map { it.asItemStack() }
				.sortedWith { a, b ->
					val aRarity = a.rarity
					val bRarity = b.rarity
					if (aRarity != bRarity) {
						return@sortedWith bRarity.compareTo(aRarity)
					}
					val aRating = a.powerLevel
					val bRating = b.powerLevel
					if (aRating != bRating) {
						return@sortedWith bRating.compareTo(aRating)
					}
					val aTier = ItemUtils.getTier(a.defData)
					val bTier = ItemUtils.getTier(b.defData)
					if (aTier != bTier) {
						return@sortedWith aTier - bTier
					}
					val aDisplayName = a.displayName
					val bDisplayName = b.displayName
					aDisplayName.compareTo(bDisplayName)
				}
			llamas.add(Entry(sd.compiledNames.joinToString(", "), items).apply { addOffer(sd) })
		}
		if (llamas.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You have no llamas.")).create()
		}
		val event = CompletableFuture<CatalogOffer?>()
		source.replyPaginated(llamas, 1, customReactions = PaginatorComponents(llamas, event)) { content, page, pageCount ->
			val llama = content.first()
			val items = llama.items
			val balances = llama.getBalancesText(profileManager)
			val embed = source.createEmbed()
				.setTitle(llama.name)
				.addFieldSeparate("Contents", items, 0, true) { it.render(showRarity = if (items.size > 25) ShowRarityOption.SHOW_DEFAULT_EMOTE else ShowRarityOption.SHOW) }
				.addField(balances.first, balances.second, false)
				.setFooter("%,d of %,d".format(page + 1, pageCount))
			MessageBuilder(embed.build())
		}
		val offerIdToPurchase = runCatching { event.await() }.getOrNull() ?: return Command.SINGLE_SUCCESS
		return purchaseOffer(source, offerIdToPurchase)
	}

	private class Entry(val name: String, val items: List<FortItemStack>) {
		val offers = mutableListOf<CatalogEntryHolder>()
		private val prices = mutableMapOf<String, CatalogItemPrice>()

		fun addOffer(offer: CatalogEntryHolder) {
			offers.add(offer)
			offer.ce.prices.forEach { prices.putIfAbsent(it.currencyType.name + ' ' + it.currencySubType, it) }
		}

		val sortedOffers get() = offers.sortedByDescending { it.getMeta("SharedDisplayPriority")?.toIntOrNull() ?: 0 }

		fun getBalancesText(profileManager: ProfileManager) = (if (prices.size == 1) "Balance" else "Balances") to prices.values.joinToString(" \u00b7 ") { it.getAccountBalanceText(profileManager) }
	}

	private class PaginatorComponents(val list: MutableList<Entry>, val event: CompletableFuture<CatalogOffer?>) : PaginatorCustomComponents<Entry> {
		private var confirmed = false

		override fun modifyComponents(rows: MutableList<ActionRow>, page: Int) {
			val row = ActionRow.of(list[page].sortedOffers.map {
				val price = it.ce.prices.first()
				var s = price.renderText()
				if (it.purchaseLimit >= 0) {
					s += " (%,d left)".format(it.purchaseLimit - it.purchasesCount)
				}
				Button.of(ButtonStyle.PRIMARY, "purchase:" + it.ce.offerId, s, price.emote()?.let(Emoji::fromEmote))
			})
			rows.add(row)
		}

		override fun handleComponent(paginator: Paginator<Entry>, item: ComponentInteraction, user: User?) {
			if (!confirmed && item.componentId.startsWith("purchase:")) {
				confirmed = true
				val offerId = item.componentId.substringAfter(":")
				event.complete(list[paginator.page].offers.first { it.ce.offerId == offerId }.ce)
				paginator.stopAndFinalizeComponents(setOf(item.componentId))
			}
		}

		override fun onEnd(collected: Map<Any, ComponentInteraction>, reason: CollectorEndReason) {
			event.complete(null)
		}
	}
}