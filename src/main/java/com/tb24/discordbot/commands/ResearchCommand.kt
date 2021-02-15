package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.attributes.CampaignProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.campaign.ClaimCollectedResources
import com.tb24.fn.model.mcpprofile.commands.campaign.PurchaseResearchStatUpgrade
import com.tb24.fn.model.mcpprofile.notifications.CollectedResourceResultNotification
import com.tb24.fn.util.format
import com.tb24.fn.util.getDateISO
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortStatType
import me.fungames.jfortniteparse.fort.enums.EFortStatType.*
import me.fungames.jfortniteparse.ue4.assets.exports.UCurveTable
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Emote
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.MessageReaction
import net.dv8tion.jda.api.entities.User

class ResearchCommand : BrigadierCommand("research", "Collect your research points, or upgrade your F.O.R.T stats.") {
	private val researchSystem by lazy { loadObject<UCurveTable>("/SaveTheWorld/Research/ResearchSystem.ResearchSystem")!! }
	private val researchPointIcon by lazy { textureEmote("/Game/UI/Foundation/Textures/Icons/Currency/T-Icon-ResearchPoint-128.T-Icon-ResearchPoint-128") }

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting research data")

	private fun execute(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		val ctx = ResearchContext(campaign)
		val message = source.complete(null, renderEmbed(source, campaign, ctx))
		if (campaign.owner.id != source.api.currentLoggedIn.id) {
			return Command.SINGLE_SUCCESS
		}
		message.addReaction(researchPointIcon!!).queue()
		for (emote in ctx.icons.values) {
			message.addReaction(emote).queue()
		}
		val collector = message.createReactionCollector({ _, user, _ -> user == source.author }, ReactionCollectorOptions().apply {
			idle = 45000L
		})
		collector.callback = object : CollectorListener<MessageReaction> {
			override fun onCollect(item: MessageReaction, user: User?) {
				try {
					if (message.member?.hasPermission(Permission.MESSAGE_MANAGE) == true) {
						item.removeReaction(source.author).queue()
					}
					val emote = item.reactionEmote
					if (emote.isEmoji) return
					if (item.reactionEmote.idLong == researchPointIcon!!.idLong) {
						val response = source.api.profileManager.dispatchClientCommandRequest(ClaimCollectedResources().apply { collectorsToClaim = arrayOf(ctx.resourceCollectorItem!!.itemId) }, "campaign").await()
						ctx.collected = response.notifications?.filterIsInstance<CollectedResourceResultNotification>()?.firstOrNull()?.loot?.items?.firstOrNull()?.quantity ?: 0
					} else {
						val statType = ctx.icons.entries.firstOrNull { it.value.idLong == emote.idLong }?.key ?: return
						val researchLevel = (campaign.stats.attributes as CampaignProfileAttributes).research_levels[statType]
						if (researchLevel >= 120 || ctx.points < (ctx.costs[statType] ?: Integer.MAX_VALUE)) return
						source.api.profileManager.dispatchClientCommandRequest(PurchaseResearchStatUpgrade().apply { statId = statType.name }, "campaign").await()
					}
					val campaignModified = source.api.profileManager.getProfileData(campaign.owner.id, "campaign")
					ctx.populateItems(campaignModified)
					message.editMessage(renderEmbed(source, campaignModified, ctx)).queue()
				} catch (e: Throwable) {
					e.printStackTrace()
					// TODO handle async errors
				}
			}

			override fun onRemove(item: MessageReaction, user: User?) {}

			override fun onDispose(item: MessageReaction, user: User?) {}

			override fun onEnd(collected: Map<Any, MessageReaction>, reason: CollectorEndReason) {
				if (reason == CollectorEndReason.IDLE && message.member?.hasPermission(Permission.MESSAGE_MANAGE) == true) {
					message.clearReactions().queue()
				}
			}
		}
		return Command.SINGLE_SUCCESS
	}

	private fun renderEmbed(source: CommandSourceStack, campaign: McpProfile, ctx: ResearchContext): MessageEmbed {
		val embed = source.createEmbed(campaign.owner)
			.setTitle("Research")
			.setDescription("%s **%,d**".format(researchPointIcon?.asMention, ctx.points) + if (ctx.collected > 0) " (+%,d)".format(ctx.collected) else "")
			.setFooter("Last collected")
		//.setThumbnail(Utils.benBotExportAsset("/Game/UI/Foundation/Textures/Icons/Quest/T-Icon-Research-128.T-Icon-Research-128"))
		ctx.resourceCollectorItem?.attributes?.getDateISO("last_updated")?.let {
			embed.setFooter("Last collected").setTimestamp(it.toInstant())
		}
		ctx.collected = 0
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
			ctx.costs[statType] = costToNextLevel
			val statIconEmote = textureEmote(statType.icon)!!
			ctx.icons[statType] = statIconEmote
			val name = "%s %s (Lv %,d)".format(statIconEmote.asMention, statType.displayName.format(), researchLevel)
			var value = "+%,d (+%,d Party)".format(currentBonusPersonal, currentBonusTeam)
			if (researchLevel < 120) {
				value += "\n**Next:** %s %,d for +%,d".format(researchPointIcon?.asMention, costToNextLevel, gainToNextLevel)
			}
			embed.addField(name, value, true)
		}
		return embed.build()
	}

	class ResearchContext {
		@JvmField var resourceCollectorItem: FortItemStack? = null
		@JvmField var points = 0
		@JvmField var collected = 0
		@JvmField val costs = mutableMapOf<EFortStatType, Int>()
		@JvmField val icons = mutableMapOf<EFortStatType, Emote>()

		constructor(campaign: McpProfile) {
			populateItems(campaign)
		}

		@Synchronized
		fun populateItems(campaign: McpProfile) {
			points = 0
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
		}
	}
}