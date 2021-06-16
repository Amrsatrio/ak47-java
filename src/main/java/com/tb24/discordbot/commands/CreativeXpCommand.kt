package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.ue4.assets.exports.UCurveTable
import me.fungames.jfortniteparse.ue4.objects.uobject.FName

class CreativeXpCommand : BrigadierCommand("creativexp", "Shows info about your daily creative XP.", arrayOf("doihavecreativexp", "cxp")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Getting BR data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val lastCreativePlaytimeTracker = athena.items.values.firstOrNull { it.templateId == "Quest:quest_br_creative_playtimetracker_4" }
				?: throw SimpleCommandExceptionType(LiteralMessage("No playtime tracker")).create()
			val (current, max) = getQuestCompletion(lastCreativePlaytimeTracker, false)
			val delta = 15
			val xpCount = loadObject<UCurveTable>("/Game/Athena/Balance/DataTables/AthenaAccoladeXP.AthenaAccoladeXP")!!.findCurve(FName.dummy("CreativeMode_15mMedal"))!!.eval(1f).toInt()
			val embed = source.createEmbed()
				.setTitle("Creative XP")
				.setDescription("`%s`\n%,d / %,d minutes played\n%,d / %,d %s".format(
					Utils.progress(current, max, 32),
					current, max,
					current / delta * xpCount, max / delta * xpCount, textureEmote("/Game/UI/Foundation/Textures/Icons/Items/T-FNBR-XPSmall-L.T-FNBR-XPSmall-L")?.asMention))
			val hasMoreXp = current < max
			if (hasMoreXp) {
				embed.appendDescription("\nLast XP grant at 🕒 " + formatDurationSeconds((235L - (max - current)) * 60L))
			}
			source.complete(if (it.commandName == "doihavecreativexp") getEmoteByName(if (hasMoreXp) "yus" else "nu")?.asMention else null, embed.build())
			Command.SINGLE_SUCCESS
		}

	private fun formatDurationSeconds(seconds: Long): String {
		val h = seconds / 3600L
		val m = (seconds % 3600L) / 60L
		val s = seconds % 60L
		return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
	}
}