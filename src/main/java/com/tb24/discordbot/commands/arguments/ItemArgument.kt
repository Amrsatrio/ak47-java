package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile

class ItemArgument(private val greedy: Boolean, private vararg val itemTypes: String) : ArgumentType<ItemArgument.Result> {
	companion object {
		@JvmStatic
		inline fun item(greedy: Boolean, vararg itemTypes: String) = ItemArgument(greedy, *itemTypes)

		@JvmStatic
		fun getItem(context: CommandContext<CommandSourceStack>, name: String, profile: McpProfile) =
			context.getArgument(name, Result::class.java).resolve(profile)
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
		return Result(query, *itemTypes)
	}

	class Result(val search: String, vararg val itemTypes: String) {
		fun resolve(profile: McpProfile): FortItemStack {
			var item = profile.items[search]
			if (item == null) {
				item = profile.items.values.firstOrNull { it.primaryAssetType in itemTypes && it.displayName.trim().equals(search, true) }
			}
			if (item == null || item.primaryAssetType !in itemTypes) {
				throw SimpleCommandExceptionType(LiteralMessage("Item not found.")).create()
			}
			return item
		}
	}
}