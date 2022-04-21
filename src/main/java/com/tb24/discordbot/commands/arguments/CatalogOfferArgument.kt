package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.commands.arguments.CatalogOfferArgument.Result
import com.tb24.discordbot.managers.CatalogManager
import com.tb24.discordbot.util.holder
import com.tb24.discordbot.util.readString0
import com.tb24.discordbot.util.safeGetOneIndexed
import com.tb24.discordbot.util.search
import com.tb24.fn.model.gamesubcatalog.CatalogOffer

class CatalogOfferArgument(val greedy: Boolean) : ArgumentType<Result> {
	companion object {
		private val CATALOG_ENTRY_UNKNOWN = SimpleCommandExceptionType(LiteralMessage("Catalog entry not found."))

		@JvmStatic
		inline fun catalogOffer(greedy: Boolean = false) = CatalogOfferArgument(greedy)

		@JvmStatic
		fun getCatalogEntry(context: CommandContext<CommandSourceStack>, name: String, loadingText: String = "Getting the shop") =
			context.getArgument(name, Result::class.java).getCatalogEntry(context.source, loadingText)
	}

	override fun parse(reader: StringReader): Result {
		val start = reader.cursor
		val query = reader.readString0(if (greedy) emptySet() else setOf(' '))

		// Parse number
		val number = query.toIntOrNull()
		if (number != null) {
			return Result(reader) { it.purchasableCatalogEntries.safeGetOneIndexed(number, reader, start) }
		}

		return Result(reader) { cm ->
			val catalogEntries = cm.catalogData!!.storefronts.flatMap { it.catalogEntries }

			// Find by offer ID
			catalogEntries.firstOrNull { it.offerId == query }?.let { return@Result it }

			// Find by offer name
			catalogEntries.search(query) { it.holder().displayData.title }
		}
	}

	class Result(
		private val reader: StringReader,
		private val supplier: (CatalogManager) -> CatalogOffer?) {
		fun getCatalogEntry(source: CommandSourceStack, loadingText: String = "Getting the shop"): CatalogOffer {
			source.ensureSession()
			val catalogManager = source.client.catalogManager
			if (catalogManager.ensureCatalogData(source.client.internalSession.api)) {
				source.loading(loadingText)
			}
			return supplier(catalogManager) ?: throw CATALOG_ENTRY_UNKNOWN.createWithContext(reader)
		}
	}
}