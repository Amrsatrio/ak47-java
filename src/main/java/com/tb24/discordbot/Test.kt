package com.tb24.discordbot

import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

fun main() {
	var fut: Future<*>? = null
	try {
		var i = 0
		fut = ScheduledThreadPoolExecutor(1).scheduleAtFixedRate({
			println("Hi there!" + ++i)
			if (i == 5) {
				fut!!.cancel(false)
				//throw RuntimeException("kek")
			}
		}, 0L, 2L, TimeUnit.SECONDS)
		fut.get()
		print("after await")
	} catch (e: RuntimeException) {
		println("i caught")
		e.printStackTrace()
	}
}