package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.util.search
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile

class ItemArgument(private val greedy: Boolean) : ArgumentType<ItemArgument.Result> {
	companion object {
		@JvmStatic
		inline fun item(greedy: Boolean) = ItemArgument(greedy)

		@JvmStatic
		fun getItem(context: CommandContext<CommandSourceStack>, name: String, profile: McpProfile, vararg itemTypes: String) =
			context.getArgument(name, Result::class.java).resolve(profile, *itemTypes)
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
			val items = if (itemTypes.isNotEmpty()) profile.items.filter { it.value.primaryAssetType in itemTypes } else profile.items
			return items[search]
				?: items.values.search(search) { it.displayName.trim() }
				?: throw SimpleCommandExceptionType(LiteralMessage("Item not found.")).create()
		}
	}
}