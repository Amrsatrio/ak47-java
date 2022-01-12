package com.tb24.discordbot.tasks

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.commands.CommandSourceStack
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

private val order = arrayOf(Technology, Offense, Fortitude, Resistance)

class AutoResearchManager(val client: DiscordBot) {
	private val logger = LoggerFactory.getLogger(javaClass)
	private val scheduler = ScheduledThreadPoolExecutor(2)
	private val scheduled = ConcurrentHashMap<String, ScheduledFuture<*>>()

	fun initSchedule() {
		val enrolledAccounts = r.table("auto_research").run(client.dbConn, AutoResearchEnrollment::class.java).toList()
		for (enrollment in enrolledAccounts) {
			schedule(enrollment)
		}
		logger.info("Scheduled {} accounts", enrolledAccounts.size)
	}

	fun schedule(enrollment: AutoResearchEnrollment, time: Long = enrollment.nextRun.time) {
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
				if (enrollment.runSuccessful) {
					schedule(enrollment)
				} else {
					client.dlog("Auto research for account ${enrollment.id} failed, will try again in 1 hour", null)
					schedule(enrollment, System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
				}
			} else {
				unenroll(enrollment.id)
			}
		}, time - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
	}

	fun unschedule(epicId: String) {
		scheduled.remove(epicId)?.cancel(false)
	}

	private fun perform(enrollment: AutoResearchEnrollment): Boolean {
		val epicId = enrollment.id
		val discordId = enrollment.registrantId
		client.setupInternalSession()
		val epicUser = client.internalSession.queryUsers(setOf(epicId)).firstOrNull()
		val displayName = epicUser?.displayName
		try {
			val user = client.discord.retrieveUserById(discordId).complete()
			val channel = user.openPrivateChannel().complete()
			val source = CommandSourceStack(client, channel)
			val savedDevice = if (displayName != null) client.savedLoginsManager.get(discordId, epicId) else null
			if (savedDevice == null) {
				unenroll(epicId)
				source.complete("Disabled auto research of `$displayName` because we couldn't login to the account.")
				return true
			}
			withDevice(source, savedDevice, { performForAccount(source, enrollment) }, { unenroll(epicId) }, mapOf(epicId to epicUser!!))
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
				r.table("auto_research").get(enrollment.id).update(enrollment).run(client.dbConn)
			}
			enrollment.nextRun
		} catch (e: CommandSyntaxException) {
			results.add("🎉 Reached max on all stats! Auto research will be disabled.")
			enrollment.rvn = -1L
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

	private fun unenroll(accountId: String) {
		r.table("auto_research").get(accountId).delete().run(client.dbConn)
	}
}

fun AutoResearchEnrollment.ensureData(source: CommandSourceStack, inCtx: ResearchViewController? = null): Boolean {
	val campaign = source.api.profileManager.getProfileData(id, "campaign")
	if (campaign.rvn == rvn) {
		return false // Already up to date
	}
	val ctx = inCtx ?: ResearchViewController(campaign, source.session.getHomebase(id)) // Will throw an exception if research is not yet unlocked

	// Check if all stats are at max
	if (ctx.stats.values.count { it.researchLevel >= 120 } >= 4) {
		throw SimpleCommandExceptionType(LiteralMessage("You have reached max research levels. There is no need to research further.")).create()
	}

	// Calculate points to collect
	var iteration = -1
	val unpurchasableStats = hashSetOf<EFortStatType>()
	val statUpgrades = hashMapOf<EFortStatType, Int>()
	var collectorTarget = 0
	while (unpurchasableStats.size < order.size) {
		val statType = order[++iteration % order.size]
		if (statType in unpurchasableStats) {
			continue
		}
		val stat = ctx.stats[statType]!!
		val previewStatLevel = stat.researchLevel + (statUpgrades[statType] ?: 0)
		val previewCollectorTarget = collectorTarget + stat.getCostToLevel(previewStatLevel + 1)
		if (previewStatLevel >= 120 || previewCollectorTarget > ctx.collectorLimit) { // Max or target exceeds collector capacity
			unpurchasableStats.add(statType)
			continue
		}
		// Stat will be upgraded
		statUpgrades[statType] = (statUpgrades[statType] ?: 0) + 1
		collectorTarget = previewCollectorTarget
	}
	nextRun = ctx.getTimeAtCollectorTarget(collectorTarget)
	rvn = campaign.rvn
	return true
}