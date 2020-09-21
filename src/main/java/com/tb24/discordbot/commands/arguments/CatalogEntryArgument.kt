package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.commands.arguments.CatalogEntryArgument.Result
import com.tb24.discordbot.util.safeGetOneIndexed
import com.tb24.fn.model.FortCatalogResponse.CatalogEntry

class CatalogEntryArgument : ArgumentType<Result> {
	companion object {
		private val CATALOG_ENTRY_UNKNOWN = SimpleCommandExceptionType(LiteralMessage("Catalog entry not found."))

		@JvmStatic
		fun catalogEntry() = CatalogEntryArgument()

		@JvmStatic
		fun getCatalogEntry(context: CommandContext<CommandSourceStack>, name: String, loadingText: String = "Getting the shop") =
			context.getArgument(name, Result::class.java).getCatalogEntry(context.source, loadingText)
	}

	override fun parse(reader: StringReader): Result {
		val start = reader.cursor
		while (reader.canRead() && reader.peek() != ' ') {
			reader.skip()
		}
		val query: String = reader.string.substring(start, reader.cursor)
		try {
			val result = query.toInt()
			return Result { s, loadingText ->
				s.client.catalogManager.apply {
					s.ensureSession()
					if (ensureCatalogData(s.api)) {
						s.loading(loadingText)
					}
				}.purchasableCatalogEntries.safeGetOneIndexed(result, reader, start)
			}
		} catch (ignored: NumberFormatException) {
		}
		return Result { s, loadingText ->
			s.client.catalogManager.apply {
				s.ensureSession()
				if (ensureCatalogData(s.api)) {
					s.loading(loadingText)
				}
			}.catalogData!!.storefronts.flatMap { it.catalogEntries.toSet() }.firstOrNull { it.offerId == query }
				?: throw CATALOG_ENTRY_UNKNOWN.createWithContext(reader.apply { cursor = start })
		}
	}

	fun interface Result {
		fun getCatalogEntry(source: CommandSourceStack, loadingText: String): CatalogEntry
	}
}