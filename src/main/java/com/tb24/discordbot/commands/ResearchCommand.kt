package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.ui.ResearchViewController
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.awaitOneInteraction
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.textureEmote
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.ClaimCollectedResources
import com.tb24.fn.model.mcpprofile.commands.campaign.PurchaseResearchStatUpgrade
import com.tb24.fn.model.mcpprofile.notifications.CollectedResourceResultNotification
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.format
import com.tb24.fn.util.getDateISO
import me.fungames.jfortniteparse.fort.enums.EFortStatType
import me.fungames.jfortniteparse.fort.enums.EFortStatType.Invalid
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.Button
import net.dv8tion.jda.api.interactions.components.ButtonStyle

class ResearchCommand : BrigadierCommand("research", "Collect your research points, or upgrade your F.O.R.T stats.") {
	private val researchPointIcon by lazy { textureEmote("/Game/UI/Foundation/Textures/Icons/Currency/T-Icon-ResearchPoint-128.T-Icon-ResearchPoint-128") }

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting research data")

	private fun execute(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		val ctx = ResearchViewController(campaign)
		if (campaign.owner.id != source.api.currentLoggedIn.id) {
			source.complete(null, renderEmbed(source, campaign, ctx))
			return Command.SINGLE_SUCCESS
		}
		while (true) {
			val buttons = mutableListOf<Button>()
			buttons.add(Button.secondary("collect", Emoji.fromEmote(researchPointIcon!!))) // TODO Show number of points to be collected
			for ((statType, stat) in ctx.stats) {
				buttons.add(Button.of(ButtonStyle.SECONDARY, statType.name, if (stat.researchLevel < 120) Formatters.num.format(stat.costToNextLevel) else "MAX", Emoji.fromEmote(textureEmote(statType.icon)!!)).withDisabled(!stat.canUpgrade()))
			}
			val message = source.complete(null, renderEmbed(source, campaign, ctx), ActionRow.of(buttons))
			source.loadingMsg = message
			val choice = message.awaitOneInteraction(source.author, false).componentId
			if (choice == "collect") {
				val response = source.api.profileManager.dispatchClientCommandRequest(ClaimCollectedResources().apply { collectorsToClaim = arrayOf(ctx.resourceCollectorItem!!.itemId) }, "campaign").await()
				ctx.collected = response.notifications?.filterIsInstance<CollectedResourceResultNotification>()?.firstOrNull()?.loot?.items?.firstOrNull()?.quantity ?: 0
			} else {
				val statType = EFortStatType.from(choice)
				check(statType != Invalid && ctx.stats[statType]!!.canUpgrade())
				source.api.profileManager.dispatchClientCommandRequest(PurchaseResearchStatUpgrade().apply { statId = statType.name }, "campaign").await()
			}
			val campaignModified = source.api.profileManager.getProfileData(campaign.owner.id, "campaign")
			ctx.populateItems(campaignModified)
		}
	}

	private fun renderEmbed(source: CommandSourceStack, campaign: McpProfile, ctx: ResearchViewController): MessageEmbed {
		val embed = source.createEmbed(campaign.owner)
			.setTitle("Research")
			.setDescription("%s **%,d**".format(researchPointIcon?.asMention, ctx.points) + if (ctx.collected > 0) " (+%,d)".format(ctx.collected) else "")
			.setFooter("Last collected")
		//.setThumbnail(Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Quest/T-Icon-Research-128.T-Icon-Research-128"))
		ctx.resourceCollectorItem?.attributes?.getDateISO("last_updated")?.let {
			embed.setFooter("Last collected").setTimestamp(it.toInstant())
		}
		ctx.collected = 0
		for ((statType, stat) in ctx.stats) {
			val name = "%s %s (Lv %,d)".format(stat.statIconEmote.asMention, statType.displayName.format(), stat.researchLevel)
			var value = "+%,d (+%,d Party)".format(stat.currentBonusPersonal, stat.currentBonusTeam)
			if (stat.researchLevel < 120) {
				value += "\n**Next:** %s %,d for +%,d".format(researchPointIcon?.asMention, stat.costToNextLevel, stat.gainToNextLevel)
			}
			embed.addField(name, value, true)
		}
		return embed.build()
	}
}