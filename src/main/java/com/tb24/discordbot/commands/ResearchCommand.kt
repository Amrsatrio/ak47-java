package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.ui.ResearchViewController
import com.tb24.discordbot.util.awaitOneInteraction
import com.tb24.discordbot.util.relativeFromNow
import com.tb24.discordbot.util.textureEmote
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.util.format
import me.fungames.jfortniteparse.fort.enums.EFortStatType
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.Button
import net.dv8tion.jda.api.interactions.components.ButtonStyle
import kotlin.math.min

val researchPointIcon by lazy { textureEmote("/Game/UI/Foundation/Textures/Icons/Currency/T-Icon-ResearchPoint-128.T-Icon-ResearchPoint-128") }

class ResearchCommand : BrigadierCommand("research", "Collect your research points, or upgrade your F.O.R.T stats.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting research data")

	private fun execute(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		val homebase = source.session.getHomebase(campaign.owner.id)
		val ctx = ResearchViewController(campaign, homebase)
		if (campaign.owner.id != source.api.currentLoggedIn.id) {
			source.complete(null, renderEmbed(source, campaign, ctx), *createComponents(ctx, false))
			return Command.SINGLE_SUCCESS
		}
		while (true) {
			val message = source.complete(null, renderEmbed(source, campaign, ctx), *createComponents(ctx, true))
			source.loadingMsg = message
			val choice = message.awaitOneInteraction(source.author, false, 90000L).componentId
			if (choice == "collect") {
				ctx.collect(source.api, homebase)
			} else {
				ctx.research(source.api, homebase, EFortStatType.from(choice))
			}
		}
	}

	private fun renderEmbed(source: CommandSourceStack, campaign: McpProfile, ctx: ResearchViewController): MessageEmbed {
		val embed = source.createEmbed(campaign.owner)
			.setTitle("Research")
			.setDescription("%s **%,d** / %,d".format(researchPointIcon?.asMention, ctx.points, ctx.pointLimit) + if (ctx.collected > 0) " (+%,d)".format(ctx.collected) else "")
		//.setThumbnail(Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Quest/T-Icon-Research-128.T-Icon-Research-128"))
		ctx.collected = 0
		for ((statType, stat) in ctx.stats) {
			val name = "%s %s (Lv %,d)".format(stat.statIconEmote.asMention, statType.displayName.format(), stat.researchLevel)
			val value = "+%,d (+%,d Party)".format(stat.currentBonusPersonal, stat.currentBonusTeam)
			embed.addField(name, value, true)
		}
		embed.addField("Details", "**Last collected:** %s\n**Generation rate:** %,d per hour\n**Collector full:** %s".format(
			ctx.collectorLastUpdated.relativeFromNow(),
			ctx.collectorRatePerHour,
			ctx.collectorFullDate.relativeFromNow()
		), false)
		return embed.build()
	}

	private fun createComponents(ctx: ResearchViewController, isSelf: Boolean): Array<ActionRow> {
		val buttons = mutableListOf<Button>()
		for ((statType, stat) in ctx.stats) {
			val researchText = if (stat.researchLevel >= 120) "MAX" else {
				"+%,d \u00b7 %,d pts".format(stat.gainToNextLevel, stat.costToNextLevel)
			}
			buttons.add(Button.of(ButtonStyle.SECONDARY, statType.name, researchText, Emoji.fromEmote(textureEmote(statType.icon)!!)).withDisabled(!isSelf || !stat.canUpgrade()))
		}
		val isMaxPoints = ctx.points >= ctx.pointLimit
		val collectText = if (isMaxPoints) "MAX" else {
			"Collect %,d/%,d pts".format(min(ctx.pointLimit - ctx.points, ctx.collectorPoints), ctx.collectorLimit)
		}
		val collectBtn = Button.of(ButtonStyle.SECONDARY, "collect", collectText, Emoji.fromEmote(researchPointIcon!!)).withDisabled(!isSelf || isMaxPoints)
		return arrayOf(ActionRow.of(buttons), ActionRow.of(collectBtn))
	}
}