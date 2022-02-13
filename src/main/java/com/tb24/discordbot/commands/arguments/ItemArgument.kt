package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.util.search
import com.tb24.discordbot.util.searchItemDefinition
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile

class ItemArgument(private val greedy: Boolean) : ArgumentType<ItemArgument.Result> {
	companion object {
		@JvmStatic
		inline fun item(greedy: Boolean) = ItemArgument(greedy)

		@JvmStatic
		fun getItem(context: CommandContext<CommandSourceStack>, name: String, profile: McpProfile, vararg itemTypes: String) =
			context.getArgument(name, Result::class.java).resolve(profile, *itemTypes)

		@JvmStatic
		fun getItemWithFallback(context: CommandContext<CommandSourceStack>, name: String, profile: McpProfile, itemType: String) =
			context.getArgument(name, Result::class.java).resolveWithFallback(profile, itemType)
	}

	override fun parse(reader: StringReader): Result {
		val query = if (StringReader.isQuotedStringStart(reader.peek())) {
			reader.readQuotedString()
		} else {
			val start = reader.cursor
			while (reader.canRead() && (greedy || reader.peek() != ' ')) {
				reader.skip()
			}
			reader.string.substring(start, reader.cursor)
		}
		return Result(query)
	}

	class Result(val search: String) {
		fun resolve(profile: McpProfile, vararg itemTypes: String): FortItemStack {
			val items = filterItems(profile, *itemTypes)
			return items[search]
				?: items.values.search(search) { it.displayName.trim() }
				?: throw SimpleCommandExceptionType(LiteralMessage("Item not found.")).create()
		}

		fun resolveWithFallback(profile: McpProfile, itemType: String): FortItemStack {
			val items = filterItems(profile, itemType)
			items[search]?.let { return it }
			val (templateId, itemDef) = if (itemType.contains(':')) {
				val (primaryAssetType, itemDefClassName) = itemType.split(':')
				searchItemDefinition(search, primaryAssetType, itemDefClassName)
			} else {
				searchItemDefinition(search, itemType)
			} ?: throw SimpleCommandExceptionType(LiteralMessage("Item not found.")).create()
			return items.values.firstOrNull { it.templateId.toLowerCase() == templateId }
				?: FortItemStack(itemDef, 1) // Will have null itemId
		}

		private fun filterItems(profile: McpProfile, vararg itemTypes: String) =
			if (itemTypes.isNotEmpty()) {
				if (itemTypes.size == 1 && itemTypes[0].contains(':')) {
					val (primaryAssetType, itemDefClassName) = itemTypes[0].split(':')
					profile.items.filter { it.value.primaryAssetType == primaryAssetType && it.value.defData?.exportType == itemDefClassName }
				} else {
					profile.items.filter { it.value.primaryAssetType in itemTypes }
				}
			} else {
				profile.items
			}
	}
}