package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.CatalogEntryHolder
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortCmsData
import com.tb24.fn.model.FortCmsData.ShopSection
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.CompletableFuture

class CatabaCommand : BrigadierCommand("cataba", "Posts the shop in text form with the new format. (BETA)") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting the shop")
			val catalogManager = source.client.catalogManager
			val profileManager = source.api.profileManager
			catalogManager.ensureCatalogData(source.api)
			CompletableFuture.allOf(
				profileManager.dispatchClientCommandRequest(QueryProfile()),
				profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
			).await()
			source.loading("Getting additional data")
			val cmsData = source.api.okHttpClient.newCall(Request.Builder().url("https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game").build()).exec().to<FortCmsData>()
			val sections = cmsData.shopSections.sectionList.sections.associate {
				val section = CatabaSection(it)
				section.cmsBacking.sectionId to section
			}
			for (storefront in catalogManager.catalogData!!.storefronts) {
				for (catalogEntry in storefront.catalogEntries) {
					val ce = catalogEntry.holder()
					(sections[ce.getMeta("SectionId") ?: continue] ?: continue).items.add(ce)
				}
			}
			val embed = source.createEmbed()
				.setColor(0x0099FF)
				.setTitle("☂ " + "Battle Royale Item Shop")
				.setTimestamp(Instant.now())
			if (source.session.id != "__internal__") {
				embed.setDescription("Use `${source.prefix}buy` or `${source.prefix}gift` to perform operations with these items.\n✅ = Owned")
				//.addField(if (prices.size == 1) "Balance" else "Balances", prices.values.joinToString(" \u00b7 ") { it.getAccountBalanceText(profileManager) }, false)
			}
			for (section in sections.values) {
				if (section.items.isEmpty()) {
					continue
				}
				section.items.sortByDescending { it.ce.sortPriority ?: 0 }
				embed.addFieldSeparate(section.cmsBacking.sectionDisplayName ?: "", section.items, 0) {
					it.resolve(source.api.profileManager)
					"${(it.ce.__ak47_index + 1)}. ${it.friendlyName}${if (it.owned) " ✅" else ""}"
				}
			}
			source.complete(null, embed.build())
			Command.SINGLE_SUCCESS
		}
}

class CatabaSection(val cmsBacking: ShopSection) {
	val items = mutableListOf<CatalogEntryHolder>()
}