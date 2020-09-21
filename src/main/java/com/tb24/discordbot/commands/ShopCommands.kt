package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.getAccountBalanceText
import com.tb24.discordbot.util.holder
import com.tb24.fn.assetdata.ESubGame
import com.tb24.fn.model.EStoreCurrencyType
import com.tb24.fn.model.FortCatalogResponse
import com.tb24.fn.model.mcpprofile.commands.PopulatePrerolledOffers
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import net.dv8tion.jda.api.EmbedBuilder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO

class ShopCommand : BrigadierCommand("shop", "Description later plz", arrayListOf("s")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Getting the shop")
			source.client.catalogManager.ensureCatalogData(source.api)
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val commonCore = source.api.profileManager.getProfileData("common_core")
			val catalogEntries = mutableListOf<FortCatalogResponse.CatalogEntry>()
			for (catalogGroup in source.client.catalogManager.athenaCatalogGroups) {
				for (catalogEntry in catalogGroup.items) {
					catalogEntries.add(catalogEntry)
				}
			}
			// fetch fortnite-api.com
//			val slots = mutableListOf<>()
			val img = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
			val g = img.createGraphics()
			g.color = Color(0x7FFF7F)
			g.drawRect(0, 0, 128, 128)
//			ImageIO.read(URL("https://"))
			source.loading("Rendering and uploading image")
			source.channel.sendFile(ByteArrayOutputStream().apply { ImageIO.write(img, "png", this) }.toByteArray(), "unknown.png").complete()
			source.loadingMsg!!.delete().queue()
			Command.SINGLE_SUCCESS
		}
}

class ShopTextCommand : BrigadierCommand("shoptext", "Sends the current item shop items as a text.", arrayListOf("st")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { executeShopText(it.source, ESubGame.Athena) }
}

class CampaignShopCommand : BrigadierCommand("stwshop", "Sends the current Save the World item shop items as a text.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { executeShopText(it.source, ESubGame.Campaign) }
}

fun executeShopText(source: CommandSourceStack, subGame: ESubGame): Int {
	if (subGame == ESubGame.Athena && isUserAnIdiot(source)) {
		return Command.SINGLE_SUCCESS
	}
	source.ensureSession()
	source.loading("Getting the shop")
	val catalogManager = source.client.catalogManager
	val profileManager = source.api.profileManager
	catalogManager.ensureCatalogData(source.api)
	profileManager.dispatchClientCommandRequest(QueryProfile()).await()
	val commonCore = profileManager.getProfileData("common_core")
	if (subGame == ESubGame.Campaign) {
		profileManager.dispatchClientCommandRequest(PopulatePrerolledOffers(), "campaign").await()
	} else {
		profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
	}
	var useEmbed = true
	val groups = if (subGame == ESubGame.Campaign) catalogManager.campaignCatalogGroups else catalogManager.athenaCatalogGroups
	val contents = arrayOfNulls<String>(groups.size)
	val prices = mutableMapOf<String, FortCatalogResponse.Price>()
	for (i in groups.indices) {
		val lines = mutableListOf<String>()
		for (catalogEntry in groups[i].items) {
/*			if (catalogEntry.shouldBeSkipped(commonCore)) {
				continue
			}*/
			if (catalogEntry.prices.isEmpty() || catalogEntry.prices.first().currencyType == EStoreCurrencyType.RealMoney) continue
			val sd = catalogEntry.holder().apply { resolve(profileManager) }
			lines.add("${(catalogEntry.__ak47_index + 1)}. ${sd.friendlyName}${if (sd.owned) " ✅" else ""}")
			catalogEntry.prices.forEach { prices.putIfAbsent(it.currencyType.name + ' ' + it.currencySubType, it) }
		}
		contents[i] = lines.joinToString("\n")
		if (contents[i]!!.length >= 1024) {
			useEmbed = false
		}
	}
	val embed = EmbedBuilder()
		.setColor(0x0099FF)
		.setTitle(if (subGame == ESubGame.Campaign) "⚡ " + "Save the World Item Shop" else "☂ " + "Battle Royale Item Shop")
		.setTimestamp(Instant.now())
	if (source.session.id != "__internal__") {
		embed.setDescription("Use `${source.prefix}buy` or `${source.prefix}gift` to perform operations with these items.\n✅ = Owned")
			.addField(if (prices.size == 1) "Balance" else "Balances", prices.values.joinToString(" \u00b7 ") { it.getAccountBalanceText(profileManager) }, false)
	}
	if (useEmbed) {
		for (i in groups.indices) {
			if (contents[i].isNullOrEmpty()) {
				continue
			}
			embed.addField(groups[i].title, contents[i], false)
		}
	}
	source.complete(null, embed.build())
	if (!useEmbed) {
		for (i in groups.indices) {
			if (contents[i].isNullOrEmpty()) {
				continue
			}
			source.channel.sendMessage("**" + groups[i].title + "**\n" + contents[i]).queue()
		}
	}
	return Command.SINGLE_SUCCESS
}

private fun isUserAnIdiot(source: CommandSourceStack): Boolean {
	if (source.channel.idLong == 709667951300706385L || source.channel.idLong == 708845713592811603L) {
		source.complete("Hey ${source.author.asMention}, in this server there is an <#702307657989619744> channel.\nIf you believe that it is outdated, you can DM one of us to update it.")
		return true
	}
	return false
}