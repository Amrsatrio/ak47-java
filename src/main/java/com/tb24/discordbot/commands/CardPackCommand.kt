package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType.*
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.item.cappedTier
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.mcpprofile.McpLootEntry
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.OpenCardPack
import com.tb24.fn.model.mcpprofile.commands.campaign.OpenCardPackBatch
import com.tb24.fn.model.mcpprofile.commands.campaign.PopulatePrerolledOffers
import com.tb24.fn.model.mcpprofile.notifications.CardPackResultNotification
import com.tb24.fn.util.getString
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.ComponentInteraction
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import java.util.concurrent.CompletableFuture

class CardPackCommand : BrigadierCommand("llamas", "Look at your llamas and open them.", arrayOf("ll", "ocp")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(argument("llama #", integer(1))
			.executes { execute(it.source, getInteger(it, "llama #")) }
		)
		.then(literal("openall")
			.executes { openNonChoiceCardPack(it.source) }
		)
		.then(literal("cp")
			.executes { openChoiceCardPack(it.source) }
		)

	override fun getSlashCommand() = newCommandBuilder()
		.option(OptionType.INTEGER, "llama-number", "The number of the llama to view")
		.executes { execute(it, it.getOption("llama-number")?.asInt ?: 1) }

	private fun execute(source: CommandSourceStack, llamaNumber: Int = 1): Int {
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
		val section = catalogManager.catalogData?.storefronts?.firstOrNull { it.name == "CardPackStorePreroll" }
			?: throw SimpleCommandExceptionType(LiteralMessage("Could not find preroll section.")).create()
		val llamas = mutableListOf<Entry>()
		for (offer in section.catalogEntries) {
			val prerollData = prerolls.firstOrNull { it.attributes.getString("offerId") == offer.offerId } ?: continue
			val sd = offer.holder().apply { resolve(profileManager) }
			val canNotPurchase = sd.owned || sd.purchaseLimit >= 0 && sd.purchasesCount >= sd.purchaseLimit
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
				.sortedWith(CardPackItemsComparator)
			llamas.add(Entry(sd.compiledNames.joinToString(", "), items).apply { addOffer(sd) })
		}
		if (llamas.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You have no llamas.")).create()
		}
		val event = CompletableFuture<CatalogOffer?>()
		llamas.safeGetOneIndexed(llamaNumber)
		source.replyPaginated(llamas, 1, llamaNumber - 1, customComponents = PaginatorComponents(llamas, event)) { content, page, pageCount ->
			val llama = content.first()
			val items = llama.items
			val embed = source.createEmbed()
				.setTitle(llama.name)
				.addFieldSeparate("Contents", items, 0, true) { it.render(showRarity = if (items.size > 25) RARITY_SHOW_DEFAULT_EMOTE else RARITY_SHOW) }
				.setFooter("%,d of %,d".format(page + 1, pageCount))
			MessageBuilder(embed.build())
		}
		val offerIdToPurchase = runCatching { event.await() }.getOrNull() ?: return Command.SINGLE_SUCCESS
		return purchaseOffer(source, offerIdToPurchase)
	}

	private fun openNonChoiceCardPack(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting STW data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = source.api.profileManager.getProfileData("campaign")
		val cardPackToOpen = campaign.items.filter { it.value.templateId.startsWith("CardPack:cardpack_", true) && !it.value.templateId.contains("choice") }
		if (cardPackToOpen.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You have no non-choice card packs.")).create()
		}
		val itemIdArray = cardPackToOpen.keys.toTypedArray()
		val opened = source.api.profileManager.dispatchClientCommandRequest(OpenCardPackBatch().apply {
			cardPackItemIds = itemIdArray
		}, "campaign").await()
		val notif = (opened.notifications.first() as CardPackResultNotification)
		val mapItemStack = notif.lootGranted.items.map {
			it.asItemStack()
		}.sortedWith(CardPackItemsComparator)
		source.replyPaginated(mapItemStack, 15) { content, page, pageCount ->
			val embed = source.createEmbed()
				.setTitle("Received")
				.setFooter("Page %d/%d".format(page + 1, pageCount))
			embed.setDescription(content.joinToString("\n") { it.render() })
			MessageBuilder(embed)
		}
		return Command.SINGLE_SUCCESS
	}

	private fun openChoiceCardPack(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting STW data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = source.api.profileManager.getProfileData("campaign")
		val cardPackToOpen = campaign.items.filter { it.value.templateId.startsWith("CardPack:cardpack_", true) && it.value.templateId.contains("choice") }
		if (cardPackToOpen.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You have no choice card packs.")).create()
		}
		val itemsReceived = mutableListOf<FortItemStack>()
		for (cardPack in cardPackToOpen.values.withIndex()) {
			println(cardPack.value.itemId)
			val options = EpicApi.GSON.fromJson(cardPack.value.attributes.getAsJsonArray("options"), Array<McpLootEntry>::class.java).map {
				it.asItemStack()
			}
			val buttons = ActionRow.of(
				Button.secondary("0", options[0].render(showIcons = false, showRarity = RARITY_HIDE, showLevelAndTier = false)),
				Button.secondary("1", options[1].render(showIcons = false, showRarity = RARITY_HIDE, showLevelAndTier = false)),
				Button.danger("end", "Stop")
			)
			val text = "%s (have: %,d)\n- OR -\n%s (have: %,d)".format(options[0].render(), campaign.items.values.filter { it.templateId == options[0].templateId }.size, options[1].render(), campaign.items.values.filter { it.templateId == options[1].templateId }.size)
			val message = source.complete(null, source.createEmbed().setTitle("Choose an item").setDescription(text).setFooter("%,d/%,d".format(cardPack.index + 1, cardPackToOpen.size)).build(), buttons)
			source.loadingMsg = message
			try {
				val choice = message.awaitOneComponent(source)
				if (choice.componentId == "end") {
					source.loadingMsg = null
					break
				}
				val res = source.api.profileManager.dispatchClientCommandRequest(OpenCardPack().apply {
					cardPackItemId = cardPack.value.itemId
					selectionIdx = choice.componentId.toInt()
				}, "campaign").await()
				(res.notifications.first() as CardPackResultNotification).lootGranted.items.forEach {
					itemsReceived.add(it.asItemStack())
				}
			} catch (e: Exception) {
				if (e is CollectorException && e.reason == CollectorEndReason.TIME) {
					source.loadingMsg = null
					break
				} else {
					throw e
				}
			}
		}
		if (itemsReceived.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No loot received")).create()
		}
		source.replyPaginated(itemsReceived, 15) { content, page, pageCount ->
			val embed = source.createEmbed()
				.setTitle("Received")
				.setFooter("Page %d/%d, %,d items".format(page + 1, pageCount, itemsReceived.size))
			embed.setDescription(content.joinToString("\n") { it.render() })
			MessageBuilder(embed)
		}
		return Command.SINGLE_SUCCESS
	}

	private class Entry(val name: String, val items: List<FortItemStack>) {
		val offers = mutableListOf<CatalogEntryHolder>()

		fun addOffer(offer: CatalogEntryHolder) {
			offers.add(offer)
		}

		val sortedOffers get() = offers.sortedByDescending { it.getMeta("SharedDisplayPriority")?.toIntOrNull() ?: 0 }
	}

	private class PaginatorComponents(val list: MutableList<Entry>, val event: CompletableFuture<CatalogOffer?>) : PaginatorCustomComponents<Entry> {
		private var confirmed = false

		override fun modifyComponents(paginator: Paginator<Entry>, rows: MutableList<ActionRow>) {
			val row = ActionRow.of(list[paginator.page].sortedOffers.map { sd ->
				val price = sd.ce.prices.first()
				val accountBalance = price.getAccountBalance(paginator.source.api.profileManager)
				var s = "%,d/%s".format(accountBalance, price.renderText())
				if (sd.purchaseLimit >= 0) {
					s += " (%,d left)".format(sd.purchaseLimit - sd.purchasesCount)
				}
				Button.of(ButtonStyle.PRIMARY, "purchase:" + sd.ce.offerId, s, price.emote())
					.withDisabled(sd.owned || sd.purchaseLimit >= 0 && sd.purchasesCount >= sd.purchaseLimit || accountBalance < price.basePrice)
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

	object CardPackItemsComparator : Comparator<FortItemStack> {
		override fun compare(a: FortItemStack, b: FortItemStack): Int {
			val rarityCmp = b.rarity.compareTo(a.rarity)
			if (rarityCmp != 0) {
				return rarityCmp
			}
			val ratingCmp = b.powerLevel.compareTo(a.powerLevel)
			if (ratingCmp != 0) {
				return ratingCmp
			}
			val tierCmp = a.defData.cappedTier - b.defData.cappedTier
			if (tierCmp != 0) {
				return tierCmp
			}
			return a.displayName.compareTo(b.displayName)
		}
	}
}