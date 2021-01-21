package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile

class ItemArgument : ArgumentType<ItemArgument.Result> {
	companion object {
		@JvmStatic
		inline fun item() = ItemArgument()

		@JvmStatic
		fun getItem(context: CommandContext<CommandSourceStack>, name: String, profile: McpProfile) =
			context.getArgument(name, Result::class.java).resolve(profile)
	}

	override fun parse(reader: StringReader): Result {
		val start = reader.cursor
		while (reader.canRead() && reader.peek() != ' ') {
			reader.skip()
		}
		val query: String = reader.string.substring(start, reader.cursor)
		return Result(query)
	}

	class Result(val search: String) {
		fun resolve(profile: McpProfile): FortItemStack {
			return profile.items[search]
				?: throw SimpleCommandExceptionType(LiteralMessage("Item not found.")).create()
		}
	}
}