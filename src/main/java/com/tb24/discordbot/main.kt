package com.tb24.discordbot

import java.util.*

fun main() {
	Scanner(System.`in`)
		.run { IntArray(nextInt()) { nextInt() } }
		.forEachIndexed { index, entry ->
			println("Case #${index + 1}:")
			for (i in entry downTo 1) {
				if (arrayOf(entry, 5, 10, 30, 60).contains(i)) {
					println("$i SECONDS TILL NEW YEAR!!")
				} else {
					println(i)
				}
			}
		}
}