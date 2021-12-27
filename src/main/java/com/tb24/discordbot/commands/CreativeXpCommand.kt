package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.RESET_HOUR_UTC
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.util.getDateISO
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.ue4.assets.exports.UCurveTable
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import java.time.ZoneOffset

class CreativeXpCommand : BrigadierCommand("creativexp", "Shows info about your daily creative XP.", arrayOf("cxp")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting BR data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val stats = athena.stats as AthenaProfileStats
		val lastCreativePlaytimeTracker = athena.items.values.firstOrNull { it.templateId == "Quest:quest_br_creative_playtimetracker_4" }
			?: throw SimpleCommandExceptionType(LiteralMessage("No playtime tracker")).create()
		val (current, max) = getQuestCompletion(lastCreativePlaytimeTracker, false)
		val delta = 15
		val xpCount = loadObject<UCurveTable>("/Game/Athena/Balance/DataTables/AthenaAccoladeXP.AthenaAccoladeXP")!!.findCurve(FName("CreativeMode_15mMedal"))!!.eval(1f).toInt()
		var playtimeXp = "`%s`\n%,d / %,d minutes played\n%,d / %,d %s".format(
			Utils.progress(current, max, 32),
			current, max,
			current / delta * xpCount, max / delta * xpCount, xpEmote?.asMention)
		val hasMoreXp = current < max
		if (hasMoreXp) {
			playtimeXp += "\nLast XP grant at 🕒 " + formatDurationSeconds((235L - (max - current)) * 60L)
		}
		val embed = source.createEmbed()
			.setTitle("Creative XP")
			.addField("Playtime", playtimeXp, true)
		val dynamicXp = stats.creative_dynamic_xp
		if (dynamicXp.timespan != 0.0f) {
			embed.addField("Gameplay (raw data)", "Bank XP: %,d\nBucket XP: %,d\nBank XP multiplier: \u00d7%f\nEarned today: %,d\nDaily excess multiplier: \u00d7%f".format(dynamicXp.bankXp, dynamicXp.bucketXp, dynamicXp.bankXpMult, dynamicXp.currentDayXp, dynamicXp.dailyExcessXpMult), true)
		}
		val loginTime = stats.quest_manager?.dailyLoginInterval
		if (loginTime != null) {
			val now = lastCreativePlaytimeTracker.attributes.getDateISO("creation_time").toInstant().atOffset(ZoneOffset.UTC)
			var next = now.withHour(RESET_HOUR_UTC).withMinute(0).withSecond(0)
			if (now > next) {
				next = next.plusDays(1)
			}
			embed.setFooter("Resets").setTimestamp(next)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun formatDurationSeconds(seconds: Long): String {
		val h = seconds / 3600L
		val m = (seconds % 3600L) / 60L
		val s = seconds % 60L
		return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
	}
}