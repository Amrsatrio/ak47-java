package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.commands.BattlePassCommand.EBattlePassPurchaseOption.*
import com.tb24.discordbot.ui.BattlePassViewController
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.AthenaSeasonItemEntryReward
import com.tb24.fn.model.assetdata.rows.AthenaBattlePassOfferPriceRow
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.util.asItemStack
import com.tb24.fn.util.countMtxCurrency
import com.tb24.uasset.JWPSerializer
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.Button
import java.util.concurrent.CompletableFuture
import kotlin.math.min

val lockEmote = textureEmote("/Game/UI/Foundation/Textures/Icons/Locks/T-Icon-Lock-128.T-Icon-Lock-128")

class BattlePassCommand : BrigadierCommand("battlepass", "Manage your Battle Pass.", arrayOf("bp")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(literal("rewards").executes { rewards(it.source) })
		.then(literal("gatherids").executes { gatherIds(it.source) })
		.then(literal("buy").executes { purchaseBattlePass(it.source) })

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("rewards", "Shows Battle Pass rewards.").executes(::rewards))
		.then(subcommand("buy", "Purchase Battle Pass or levels.").executes(::purchaseBattlePass))

	private fun rewards(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting BR data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val context = BattlePassViewController(source.api.profileManager.getProfileData("athena"))
		source.replyPaginated(context.rewards.pages, 1) { (page), pageNum, pageCount ->
			val embed = EmbedBuilder().setColor(if (context.stats.book_purchased) 0xE1784D else 0x55C5FF)
				.setAuthor("%s / Battle Pass".format(getFriendlySeasonText(context.stats.season_num)), null, Utils.benBotExportAsset(if (context.stats.book_purchased) {
					"/Game/UI/Foundation/Textures/Icons/Items/T-FNBR-BattlePass.T-FNBR-BattlePass"
				} else {
					"/Game/UI/Foundation/Textures/Icons/Items/T-FNBR-BattlePass-Default.T-FNBR-BattlePass-Default"
				}))
				.setTitle("Page %,d".format(pageNum + 1))
				.setDescription(if (page.isUnlocked) {
					"**%,d** / %,d claimed\n`%s`".format(page.purchased, page.entries.size, Utils.progress(page.purchased, page.entries.size, 32))
				} else {
					"%s Claim **%,d more rewards** or reach **Level %,d** to unlock page!".format(lockEmote?.asMention, page.backing.RewardsNeededForUnlock - page.section.purchased, page.backing.LevelsNeededForUnlock)
				})
				.addField("Offers", page.entries.joinToString("\n") { it.render(context.stats) }, false)
				.addField("Balance", "%s %,d".format(battleStarEmote?.asMention, context.stats.battlestars), false)
				.setFooter("Page %,d of %,d".format(pageNum + 1, pageCount))
			MessageBuilder(embed)
		}
		return Command.SINGLE_SUCCESS
	}

	private fun gatherIds(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting BR data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val context = BattlePassViewController(athena)
		val ids = mutableListOf<String>()
		context.rewards.gatherOfferIds(ids)
		context.bonuses.gatherOfferIds(ids)
		val s = JWPSerializer.GSON.newBuilder().setPrettyPrinting().create().toJson(ids)
		if (("```json\n\n```".length + s.length) > Message.MAX_CONTENT_LENGTH) {
			val fileName = "BPOfferIds-%s-%d.json".format(source.api.currentLoggedIn.id, athena.rvn)
			source.complete(AttachmentUpload(s.toByteArray(), fileName))
		} else {
			source.complete("```json\n$s\n```")
		}
		return Command.SINGLE_SUCCESS
	}

	private fun BattlePassViewController.Section.gatherOfferIds(ids: MutableList<String>) {
		for (page in pages) {
			if (!page.isUnlocked) continue
			val deferred = mutableListOf<BattlePassViewController.Entry>()
			for (entry in page.entries) {
				if (entry.purchaseRecord == null) continue
				val data = entry.backing as AthenaSeasonItemEntryReward
				val rewardsNeededForUnlock = data.RewardsNeededForUnlock ?: 0
				if (rewardsNeededForUnlock != 0) {
					deferred.add(entry)
				} else {
					ids.add(data.BattlePassOffer.OfferId)
				}
			}
			for (entry in deferred) {
				val data = entry.backing as AthenaSeasonItemEntryReward
				ids.add(data.BattlePassOffer.OfferId)
			}
		}
	}

	private fun BattlePassViewController.Entry.render(stats: AthenaProfileStats): String {
		if (backing is AthenaSeasonItemEntryReward) {
			val locked = !page.isUnlocked || (!backing.bIsFreePassReward && !stats.book_purchased) // TODO RequiredItems
			val offer = backing.BattlePassOffer
			val item = offer.RewardItem.asItemStack()
			val price = offer.OfferPriceRowHandle.getRowMapped<AthenaBattlePassOfferPriceRow>()!!
			val priceText = if (price.Cost != -1) {
				val priceItem = price.asItemStack()
				"%s %,d".format(getItemIconEmoji(priceItem)?.asMention, priceItem.quantity)
			} else {
				"Included!"
			}
			return "`%d` %s%s [%s]".format(index + 1, if (locked) lockEmote?.asMention + " " else "", item.renderWithIcon(), priceText) + if (purchaseRecord != null) " ✅" else ""
		}
		return "Can't render: " + backing.javaClass.simpleName
	}

	private fun AthenaBattlePassOfferPriceRow.asItemStack() = FortItemStack(
		CurrencyItemTemplate.PrimaryAssetType.Name.toString(),
		CurrencyItemTemplate.PrimaryAssetName.toString().toLowerCase(),
		Cost)

	private fun purchaseBattlePass(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting BR data")
		CompletableFuture.allOf(
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()),
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
		).await()
		val balance = countMtxCurrency(source.api.profileManager.getProfileData("common_core"))
		val athena = source.api.profileManager.getProfileData("athena")
		val stats = athena.stats as AthenaProfileStats
		source.client.catalogManager.ensureCatalogData(source.client.internalSession.api)
		val storefront = source.client.catalogManager.catalogData!!.storefronts.first { it.name == "BRSeason" + stats.season_num }
		val offers = mutableListOf<CatalogEntryHolder>()
		val buttons = mutableListOf<Button>()
		val mtxEmote = Emoji.fromEmote(source.client.discord.getEmoteById(751101530626588713L)!!)
		for (option in values()) {
			val devName = "BR.Season%d.%s.01".format(stats.season_num, option)
			val offer = storefront.catalogEntries.first { it.devName == devName }.holder().apply { resolve(source.api.profileManager) }
			offers.add(offer)
			if ((option.maxLevel == -1 || stats.level <= option.maxLevel) && offer.canPurchase && balance >= offer.price.basePrice) {
				buttons.add(Button.secondary(option.name, "%,d \u00b7 %s".format(offer.price.basePrice, option.displayName)).withEmoji(mtxEmote))
			}
		}
		if (buttons.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("No Battle Pass offers available.")).create()
		}
		val botMessage = source.complete("**Which Battle Pass offer would you like to purchase?**\nYour level: %s Level %,d\nBalance: %s %,d\n".format(if (stats.book_purchased) "Battle Pass" else "Free Pass", stats.level, Utils.MTX_EMOJI, balance), null, *buttons.chunked(5, ActionRow::of).toTypedArray())
		source.loadingMsg = botMessage
		val interaction = botMessage.awaitMessageComponentInteractions({ _, user, _ -> user == source.author }, AwaitMessageComponentInteractionsOptions().apply {
			max = 1
			time = 30000
			errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		}).await().first()
		interaction.deferEdit().queue()
		val choice = valueOf(interaction.componentId)
		val offer = offers[choice.ordinal]
		var quantity = 1
		if (choice == SingleTier) {
			val limit = min(balance / offer.price.basePrice, 100 - stats.book_level)
			if (limit > 1) {
				source.complete("Enter the number of tiers you want to buy (1 - %,d, ⏱ 60s)".format(limit))
				source.loadingMsg = botMessage
				quantity = source.channel.awaitMessages({ _, user, _ -> user == source.author }, AwaitMessagesOptions().apply {
					max = 1
					time = 60000
					errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
				}).await().first().contentRaw.toIntOrNull()
					?: throw SimpleCommandExceptionType(LiteralMessage("The provided input is not a number.")).create()
				if (quantity < 1 || quantity > limit) {
					throw SimpleCommandExceptionType(LiteralMessage("Invalid input (%,d). Please do the command again.".format(quantity))).create()
				}
			}
		}
		return purchaseOffer(source, offer.ce, quantity)
	}

	enum class EBattlePassPurchaseOption(val displayName: String, val maxLevel: Int) {
		BattlePass("Battle Pass", -1),
		BP25Levels("25 Levels", 75),
		SingleTier("Individual Levels", 99)
	}
}