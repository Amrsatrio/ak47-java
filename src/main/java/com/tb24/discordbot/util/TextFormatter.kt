package com.tb24.discordbot.util

import com.tb24.fn.util.Formatters

//fun main() {
//	println(TextFormatter.format("{ItemCount} {ItemCount}|plural(one=item,other=items) to be recycled", mapOf("ItemCount" to 2)))
//	println(TextFormatter.format("Assign this Survivor to a Squad to increase Squad power and gain Fortitude, Offense, Resistance or Tech. Assign {Gender}|gender(him, her) to a squad where the Lead Survivor is [Worker.Personality] to gain more power. Match set bonuses with other Survivors to gain [Worker.SetBonus.Buff].", mapOf("Gender" to 1)))
//}

/**
 * @author FabianFG#6822
 */
object TextFormatter {
	private val regex = "\\{(.*?)\\}".toRegex()

	@JvmStatic
	fun format(s: String, vars: Map<String, Int>): String {
		var data = s
		if (vars.isEmpty()) {
			return s
		}
		val index = regex.findAll(data)
		var difference = 0
		index.iterator().forEach {
			val key = it.value.substring(1, it.value.length - 1)
			val value = vars[key]
			if (value != null) {
				val range = (it.range.first + difference) until (it.range.last + 1 + difference)
				val length = data.length
				data = if (it.range.last + 1 < s.length && s[it.range.last + 1] == '|') {
					val func = s.substring(it.range.last + 2).takeWhile { c -> c != ')' } + ")"
					data.replaceRange(range.first until range.last + func.length + 2, formatFunc(func, value))
				} else {
					data.replaceRange(range, Formatters.num.format(value))
				}
				difference += (data.length - length)
			}
		}
		return data
	}

	@JvmStatic
	fun formatFunc(s: String, value: Int): String {
		val funName = s.takeWhile { it != '(' }
		val funValue = s.substringAfter("$funName(").removeSuffix(")")
		return when (funName) {
			"plural" -> {
				var out = funValue.substringAfter("other=")
				out = when {
					value == 1 -> funValue.substringAfter("one=", out)
					value == 0 -> funValue.substringAfter("zero=", out)
					else -> out
				}
				out.takeWhile { it != ',' && it != ')' }
			}
			"gender" -> {
				val genders = funValue.split(",\\s+".toRegex())
				when (value) {
					0, 1 -> genders[0]
					2 -> genders[1]
					else -> s
				}
			}
			else -> s
		}
	}
}