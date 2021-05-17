package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.SetActiveHeroLoadout
import com.tb24.fn.model.mcpprofile.item.FortCampaignHeroLoadoutItem
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.fn.util.format
import com.tb24.fn.util.getPreviewImagePath
import me.fungames.jfortniteparse.fort.exports.FortAbilityKit
import me.fungames.jfortniteparse.fort.exports.FortHeroGameplayDefinition
import me.fungames.jfortniteparse.fort.exports.FortHeroType
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.MessageReaction
import net.dv8tion.jda.api.entities.User
import java.util.concurrent.CompletableFuture
import kotlin.math.max

class HeroLoadoutCommand : BrigadierCommand("heroloadout", "Manages your STW hero loadouts.", arrayOf("hl")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting hero loadouts")

	private fun execute(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
		val attrs = campaign.stats as CampaignProfileStats
		val loadoutsMap = sortedMapOf<Int, FortItemStack>()
		for (item in campaign.items.values) {
			if (item.primaryAssetType != "CampaignHeroLoadout") {
				continue
			}
			val loadoutAttrs = item.getAttributes(FortCampaignHeroLoadoutItem::class.java)
			loadoutsMap[loadoutAttrs.loadout_index] = item
		}
		val isMine = source.api.currentLoggedIn.id == campaign.owner.id
		val event = CompletableFuture<FortItemStack?>()
		val available = loadoutsMap.values.toList()
		source.message.replyPaginated(available, 1, source.loadingMsg, max(available.indexOfFirst { it.itemId == attrs.selected_hero_loadout }, 0), if (isMine) HeroLoadoutReactions(available, event) else null) { content, page, pageCount ->
			val loadoutItem = content[0]
			val loadoutAttrs = loadoutItem.getAttributes(FortCampaignHeroLoadoutItem::class.java)
			val embed = source.createEmbed(campaign.owner)
				.setTitle("Hero Loadout %,d".format(loadoutAttrs.loadout_index + 1))
				.setFooter("%,d of %,d".format(page + 1, pageCount) + if (loadoutItem.itemId == attrs.selected_hero_loadout) " (current)" else "")
			val commanderItem = campaign.items[loadoutAttrs.crew_members.commanderslot]
			if (commanderItem != null) {
				embed.addField("Commander", commanderItem.displayName, false)
				embed.setThumbnail(Utils.benBotExportAsset(commanderItem.getPreviewImagePath(true)?.toString()))
			} else {
				embed.addField("Commander", "Empty", false)
			}
			embed.addField("Team Perk", campaign.items[loadoutAttrs.team_perk]?.displayName ?: "Empty", false)
			embed.addField("Support Team", arrayOf(loadoutAttrs.crew_members.followerslot1, loadoutAttrs.crew_members.followerslot2, loadoutAttrs.crew_members.followerslot3, loadoutAttrs.crew_members.followerslot4, loadoutAttrs.crew_members.followerslot5).joinToString("\n") {
				val heroItem = campaign.items[it]
				if (heroItem != null) {
					val heroDef = heroItem.defData as? FortHeroType
					val gameplayDef = heroDef?.HeroGameplayDefinition?.load<FortHeroGameplayDefinition>()
					val heroPerkAbilityKit = gameplayDef?.HeroPerk?.GrantedAbilityKit?.load<FortAbilityKit>()
					heroPerkAbilityKit?.DisplayName?.format() ?: "<Unknown hero perk>"
				} else "Empty"
			}, false)
			embed.addField("Gadgets", Array(2, loadoutAttrs::getGadget).joinToString("\n") {
				if (it.isNotEmpty()) {
					val gadgetItem = FortItemStack(it, 1)
					gadgetItem.renderWithIcon()
				} else "Empty"
			}, false)
			MessageBuilder(embed.build()).build()
		}
		if (!isMine) {
			return Command.SINGLE_SUCCESS
		}
		source.loadingMsg = null
		val loadoutToApply = runCatching { event.await() }.getOrNull() ?: return Command.SINGLE_SUCCESS
		source.loading("Changing hero loadout")
		source.api.profileManager.dispatchClientCommandRequest(SetActiveHeroLoadout().apply { selectedLoadout = loadoutToApply.itemId }, "campaign").await()
		source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
			.setDescription("✅ Now using loadout %,d!".format(loadoutToApply.getAttributes(FortCampaignHeroLoadoutItem::class.java).loadout_index + 1))
			.build())
		return Command.SINGLE_SUCCESS
	}

	private class HeroLoadoutReactions(val list: List<FortItemStack>, val event: CompletableFuture<FortItemStack?>) : PaginatorCustomReactions<FortItemStack> {
		var confirmed = false

		override fun addReactions(reactions: MutableCollection<String>) {
			reactions.add("✅")
		}

		override fun handleReaction(collector: ReactionCollector, item: MessageReaction, user: User?, page: Int, pageCount: Int) {
			if (!confirmed && item.reactionEmote.name == "✅") {
				confirmed = true
				event.complete(list[page])
				collector.stop()
			}
		}

		override fun onEnd(collected: Map<Any, MessageReaction>, reason: CollectorEndReason) {
			event.complete(null)
		}
	}
}