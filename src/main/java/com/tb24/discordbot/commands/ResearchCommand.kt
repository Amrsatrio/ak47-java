package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.textureEmote
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.attributes.CampaignProfileAttributes
import com.tb24.fn.util.format
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortStatType.*
import me.fungames.jfortniteparse.ue4.assets.exports.UCurveTable
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.entities.MessageEmbed

class ResearchCommand : BrigadierCommand("research", "Collect your research points, or upgrade your F.O.R.T stats.") {
	val researchSystem by lazy { loadObject<UCurveTable>("/SaveTheWorld/Research/ResearchSystem.ResearchSystem")!! }
	val researchPointIcon by lazy { textureEmote("/Game/UI/Foundation/Textures/Icons/Currency/T-Icon-ResearchPoint-128.T-Icon-ResearchPoint-128") }

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting research data")

	private fun execute(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		var resourceCollectorItem: FortItemStack? = null
		var points = 0
		for (item in campaign.items.values) {
			if (item.templateId == "CollectedResource:Token_collectionresource_nodegatetoken01") {
				resourceCollectorItem = item
			} else if (item.templateId == "Token:collectionresource_nodegatetoken01") {
				points += item.quantity
			}
		}
		if (resourceCollectorItem == null) {
			throw SimpleCommandExceptionType(LiteralMessage("Please complete the Audition quest (one quest after Stonewood SSD 3) to unlock Research.")).create()
		}
		source.complete(null, renderEmbed(source, campaign, points))
		return Command.SINGLE_SUCCESS
	}

	private fun renderEmbed(source: CommandSourceStack, campaign: McpProfile, points: Int): MessageEmbed {
		val embed = source.createEmbed(campaign.owner)
			.setTitle("Research")
			.setDescription("%s **%,d**".format(researchPointIcon?.asMention, points))
		//.setThumbnail(Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Quest/T-Icon-Research-128.T-Icon-Research-128"))
		for (statType in arrayOf(Fortitude, Offense, Resistance, Technology)) {
			val researchLevel = (campaign.stats.attributes as CampaignProfileAttributes).research_levels[statType]
			val s = statType.name.toLowerCase()
			val cost = FName.dummy(s + "_cost")
			val personal = FName.dummy(s + "_personal")
			val personal_cumulative = FName.dummy(s + "_personal_cumulative")
			val team = FName.dummy(s + "_team")
			val team_cumulative = FName.dummy(s + "_team_cumulative")
			val gainToNextLevel = researchSystem.findCurve(personal)!!.eval(researchLevel + 1f).toInt() + researchSystem.findCurve(team)!!.eval(researchLevel + 1f).toInt()
			val currentBonusPersonal = researchSystem.findCurve(personal_cumulative)!!.eval(researchLevel.toFloat()).toInt()
			val currentBonusTeam = researchSystem.findCurve(team_cumulative)!!.eval(researchLevel.toFloat()).toInt()
			val costToNextLevel = researchSystem.findCurve(cost)!!.eval(researchLevel + 1f).toInt()
			val name = "%s %s (Lv %,d)".format(textureEmote(statType.icon)?.asMention, statType.displayName.format(), researchLevel)
			var value = "+%,d (+%,d Party)".format(currentBonusPersonal, currentBonusTeam)
			if (researchLevel < 120) {
				value += "\n**Next:** %s %,d for +%,d".format(researchPointIcon?.asMention, costToNextLevel, gainToNextLevel)
			}
			embed.addField(name, value, true)

			//val response = source.api.profileManager.dispatchClientCommandRequest(PurchaseResearchStatUpgrade().apply { statId = statType.toString() }, "campaign").await()
		}
		return embed.build()
	}
}