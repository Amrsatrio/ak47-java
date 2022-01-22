package com.tb24.discordbot.tasks

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.commands.CommandSourceStack
import com.tb24.discordbot.commands.createResearchViewController
import com.tb24.discordbot.commands.researchPointIcon
import com.tb24.discordbot.model.AutoResearchEnrollment
import com.tb24.discordbot.ui.ResearchViewController
import com.tb24.discordbot.util.*
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.format
import me.fungames.jfortniteparse.fort.enums.EFortStatType
import me.fungames.jfortniteparse.fort.enums.EFortStatType.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val order = arrayOf(Technology, Offense, Fortitude, Resistance)

class AutoResearchManager(val client: DiscordBot) {
	private val logger = LoggerFactory.getLogger(javaClass)
	private val scheduler = ScheduledThreadPoolExecutor(2)
	private val scheduled = ConcurrentHashMap<String, ScheduledFuture<*>>()
	private val isEmergencyStopped = AtomicBoolean(false)

	fun initSchedule() {
		scheduler.schedule({
			val enrolledAccounts = r.table("auto_research").run(client.dbConn, AutoResearchEnrollment::class.java).toList()
			for (enrollment in enrolledAccounts) {
				schedule(enrollment)
			}
			logger.info("Scheduled {} accounts", enrolledAccounts.size)
		}, 30L, TimeUnit.SECONDS) // Give it enough time to wait for the emote guilds to be loaded
	}

	fun schedule(enrollment: AutoResearchEnrollment) {
		if (isEmergencyStopped.get()) {
			throw SimpleCommandExceptionType(LiteralMessage("Emergency stop is in effect. Cannot schedule any more accounts.")).create()
		}
		val time = enrollment.nextRun.time
		logger.info("{}: Scheduled at {}", enrollment.id, Date(time))
		scheduled[enrollment.id] = scheduler.schedule({
			scheduled.remove(enrollment.id)
			var attempts = 5
			while (attempts-- > 0) {
				logger.info("Performing auto research for account ${enrollment.id}, attempt ${5 - attempts}")
				if (perform(enrollment)) {
					break
				}
			}
			if (enrollment.rvn != -1L) {
				if (!enrollment.runSuccessful) {
					client.dlog("Auto research for account ${enrollment.id} failed, will try again in 1 hour", null)
					enrollment.nextRun = Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
					save(enrollment)
				}
				schedule(enrollment)
			}
		}, time - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
	}

	fun unschedule(epicId: String) {
		scheduled.remove(epicId)?.cancel(false)
	}

	private fun perform(enrollment: AutoResearchEnrollment): Boolean {
		val epicId = enrollment.id
		val discordId = enrollment.registrantId
		client.ensureInternalSession()
		val epicUser = client.internalSession.queryUsers(setOf(epicId)).firstOrNull()
		val displayName = epicUser?.displayName
		try {
			val user = client.discord.retrieveUserById(discordId).complete()
			val channel = user.openPrivateChannel().complete()
			val source = CommandSourceStack(client, channel)
			val savedDevice = if (displayName != null) client.savedLoginsManager.get(discordId, epicId) else null
			if (savedDevice == null) {
				unenroll(enrollment)
				source.complete("Disabled auto research of `$displayName` because we couldn't login to the account.")
				return true
			}
			withDevice(source, savedDevice, { performForAccount(source, enrollment) }, { unenroll(enrollment) }, mapOf(epicId to epicUser!!))
			return true
		} catch (e: IOException) {
			client.dlog("Failed to research for ${displayName ?: epicId} (registered by <@$discordId>). Retrying\n$e", null)
			logger.warn("Failed to research for ${enrollment.id}. Retrying", e)
			return false
		} catch (e: Throwable) {
			client.dlog("Failed to research for ${displayName ?: epicId} (registered by <@$discordId>)\n$e", null)
			logger.warn("Failed to research for ${enrollment.id}", e)
			return true
		}
	}

	private fun performForAccount(source: CommandSourceStack, enrollment: AutoResearchEnrollment) {
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = source.api.profileManager.getProfileData("campaign")
		val homebase = source.session.getHomebase(enrollment.id)
		val ctx = ResearchViewController(campaign, homebase)
		val results = mutableListOf<String>()

		// Collect
		ctx.collect(source.api, homebase)
		results.add("Collected %s %,d".format(researchPointIcon?.asMention, ctx.collected))

		// Research
		var iteration = -1
		val unpurchasableStats = hashSetOf<EFortStatType>()
		while (unpurchasableStats.size < order.size) {
			val statType = order[++iteration % order.size]
			check(iteration < 1000) { "Too many iterations" }
			if (statType in unpurchasableStats) {
				continue
			}
			val stat = ctx.stats[statType]!!
			if (stat.researchLevel >= 120 || ctx.points < stat.costToNextLevel) { // Max or not enough points
				unpurchasableStats.add(statType)
				continue
			}
			ctx.research(source.api, homebase, statType)
			results.add("%s %s: Lv %,d \u2192 Lv %,d for %s %,d".format(textureEmote(statType.icon)?.asMention, statType.displayName.format(), stat.researchLevel, stat.researchLevel + 1, researchPointIcon?.asMention, stat.costToNextLevel))
		}

		// Schedule next
		val nextRun = try {
			if (enrollment.ensureData(source, ctx)) {
				save(enrollment)
			}
			enrollment.nextRun
		} catch (e: CommandSyntaxException) {
			results.add(e.rawMessage.string + " Auto research will be disabled.")
			unenroll(enrollment)
			null
		}

		val embed = source.createEmbed()
			.setTitle("Auto research summary")
			.setDescription(results.joinToString("\n"))
		if (nextRun != null) {
			embed.addField("Next run", nextRun.relativeFromNow(), false)
		}
		source.complete(null, embed.build())
		enrollment.runSuccessful = true
	}

	private fun save(enrollment: AutoResearchEnrollment) {
		r.table("auto_research").get(enrollment.id).update(enrollment).run(client.dbConn)
	}

	private fun unenroll(enrollment: AutoResearchEnrollment) {
		r.table("auto_research").get(enrollment.id).delete().run(client.dbConn)
		enrollment.rvn = -1L
	}

	fun emergencyStop() {
		scheduler.shutdownNow()
		scheduled.clear()
		isEmergencyStopped.set(true)
	}
}

fun AutoResearchEnrollment.ensureData(source: CommandSourceStack, inCtx: ResearchViewController? = null): Boolean {
	val campaign = source.api.profileManager.getProfileData(id, "campaign")
	if (campaign.rvn == rvn) {
		return false // Already up to date
	}
	val ctx = inCtx ?: createResearchViewController(campaign, source.session.getHomebase(id)) // Will throw an exception if research is not yet unlocked

	// Check for:
	// - All stats are at max
	// - No stats can be upgraded until the account is progressed because point limit is too low
	var statsAtMax = 0
	var cheapestUpgradeCost = Int.MAX_VALUE
	for (stat in ctx.stats.values) {
		if (stat.researchLevel >= 120) {
			++statsAtMax
		}
		if (stat.costToNextLevel < cheapestUpgradeCost) {
			cheapestUpgradeCost = stat.costToNextLevel
		}
	}
	if (statsAtMax >= 4) {
		throw SimpleCommandExceptionType(LiteralMessage("You have reached max research levels.")).create()
	}
	if (cheapestUpgradeCost > ctx.pointLimit) {
		throw SimpleCommandExceptionType(LiteralMessage("Please increase the account level in order to continue researching. The cheapest stat upgrade cost for you (%,d) is higher than your point limit (%,d).".format(cheapestUpgradeCost, ctx.pointLimit))).create()
	}

	// Update next run time
	nextRun = ctx.collectorFullDate
	rvn = campaign.rvn
	return true
}