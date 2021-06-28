package com.tb24.discordbot.commands.arguments

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.commands.CommandSourceStack
import kotlin.jvm.JvmStatic as S

class StringArgument2(private val greedy: Boolean) : ArgumentType<String> {
	companion object {
		@S inline fun string2(greedy: Boolean = false) = StringArgument2(greedy)
		@S fun getString2(context: CommandContext<CommandSourceStack>, name: String) = context.getArgument(name, String::class.java)
	}

	override fun parse(reader: StringReader) =
		if (StringReader.isQuotedStringStart(reader.peek())) {
			reader.readQuotedString()
		} else {
			val start = reader.cursor
			while (reader.canRead() && (greedy || reader.peek() != ' ')) {
				reader.skip()
			}
			reader.string.substring(start, reader.cursor)
		}
}