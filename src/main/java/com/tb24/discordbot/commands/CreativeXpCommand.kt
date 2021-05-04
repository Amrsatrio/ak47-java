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
			source.complete(if (it.commandName == "doihavecreativexp") getEmoteByName(if (current < max) "yus" else "nu")?.asMention else null, source.createEmbed()
				.setTitle("Creative XP")
				.setDescription("`%s`\n%,d / %,d minutes played\n%,d / %,d %s".format(
					Utils.progress(current, max, 32),
					current, max,
					current / delta * xpCount, max / delta * xpCount, getEmoteByName("AthenaSeasonalXP")?.asMention))
				.build())
			Command.SINGLE_SUCCESS
		}
}