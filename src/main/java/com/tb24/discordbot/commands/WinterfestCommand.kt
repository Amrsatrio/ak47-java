package com.tb24.discordbot.commands

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.assetdata.FortWinterfestData
import com.tb24.fn.model.mcpprofile.McpLootEntry
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.athena.UnlockRewardNode
import com.tb24.fn.model.mcpprofile.commands.subgame.ClientQuestLogin
import com.tb24.fn.model.mcpprofile.item.AthenaRewardEventGraphItem
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.AthenaRewardEventGraph
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.SelectionMenu
import net.dv8tion.jda.api.interactions.components.selections.SelectionMenuInteraction

class WinterfestCommand : BrigadierCommand("winterfest", "Visit the Winterfest lodge.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		val winterfestData = loadObject<FortWinterfestData>("/WinterfestFrontend/UI/Data/WinterfestScreenData.WinterfestScreenData")
			?: throw SimpleCommandExceptionType(LiteralMessage("No Winterfest right now.")).create()
		source.loading("Getting Winterfest data")
		source.api.profileManager.dispatchClientCommandRequest(ClientQuestLogin(), "athena").await()
		return display(source, winterfestData)
	}

	private fun display(source: CommandSourceStack, winterfestData: FortWinterfestData): Int {
		var athena = source.api.profileManager.getProfileData("athena")
		if (DiscordBot.ENV == "dev") {
			athena.items["FAKE_W19_REWARD_GRAPH"] = FortItemStack(winterfestData.WinterfestItemTemplateId, 1).apply {
				attributes = JsonObject().apply {
					addProperty("unlock_epoch", "2021-12-14T14:00:00Z")
					add("reward_nodes_claimed", JsonArray().apply {
						add("ERG.Node.A.2")
					})
					add("reward_keys", JsonArray().apply {
						add(JsonObject().apply {
							addProperty("static_key_template_id", "Token:athena_s19_winterfest_key")
							addProperty("unlock_keys_used", "1")
						})
					})
				}
			}
			athena.items["FAKE_W19_REWARD_KEY"] = FortItemStack(winterfestData.WinterfestKeyTemplateId, 5)
		}
		val state = WinterfestState(winterfestData, athena)
		val descriptionParts = mutableListOf("Welcome to the cabin!")

		// Key count
		val unusedKeys = state.unusedKeys
		if (unusedKeys > 0) {
			descriptionParts.add("Open **%,d presents!**".format(unusedKeys))
		} else {
			descriptionParts.add("Come back every day to open a new present!")
		}

		// Next key
		val nextKeyAt = state.nextKeyAt
		if (nextKeyAt != -1L) {
			descriptionParts.add("Next present %s".format(nextKeyAt.relativeFromNow()))
		}

		val embed = source.createEmbed()
			.setTitle("Winterfest %d \u00b7 Day %,d".format(winterfestData.WinterfestYear, state.dayNumber))
		descriptionParts.joinTo(embed.descriptionBuilder, "\n")

		// Presents
		val rewards = state.sortedRewards
		rewards.forEach { it.addTo(embed) }

		// Prompt to open a present
		var select: SelectionMenu.Builder? = null
		if (unusedKeys > 0) {
			select = SelectionMenu.create("rewardNodeId").setPlaceholder("Open a present...")
			for (reward in rewards) {
				if (reward.isClaimable) {
					select.addOption(reward.rewardNode.Rewards.joinToString { it.safeRender() }.take(SelectOption.LABEL_MAX_LENGTH).ifEmpty { reward.longDisplayName }, reward.rewardNode.NodeTag.toString())
				}
			}
			if (select.options.isEmpty()) {
				select = null
			}
		}

		if (select == null) {
			source.complete(null, embed.build())
			return Command.SINGLE_SUCCESS // Nothing more we can do
		}

		// Open present selection
		val message = source.complete(null, embed.build(), ActionRow.of(select.build()))
		source.loadingMsg = message
		val rewardNodeTagToOpen = (message.awaitOneInteraction(source.author, false, 120000L) as SelectionMenuInteraction).values.first() // 2 minutes is enough for people to glance at
		val response = source.api.profileManager.dispatchClientCommandRequest(UnlockRewardNode().apply {
			nodeId = rewardNodeTagToOpen
			rewardGraphId = state.rewardGraphItem.itemId
			rewardCfg = ""
		}, "athena").await()
		if (response.profileRevision == response.profileChangesBaseRevision) {
			source.loadingMsg = null
			message.finalizeComponents(emptySet())
			throw SimpleCommandExceptionType(LiteralMessage("Something went wrong when opening a present.")).create()
		}
		athena = source.api.profileManager.getProfileData("athena")

		// Grab the received items from the newly granted gift box
		val giftBoxTemplateId = "GiftBox:gb_winterfestreward" // Let's hardcode the gift box template ID for now, grabbing from the data is pain
		val lootListJson = athena.items.values.firstOrNull { it.templateId == giftBoxTemplateId }?.attributes?.getAsJsonArray("lootList")
		val lootList = lootListJson?.let { EpicApi.GSON.fromJson(it, Array<McpLootEntry>::class.java) }
		val claimedEmbed = source.createEmbed().setColor(COLOR_SUCCESS)
			.setTitle("✅ Claimed %s".format(state.rewards[rewardNodeTagToOpen]?.longDisplayName ?: rewardNodeTagToOpen))
		if (lootList != null) {
			claimedEmbed.addFieldSeparate("You received", lootList.toList(), 0) { it.asItemStack().render(showType = true) }
		}
		source.channel.sendMessageEmbeds(claimedEmbed.build()).queue()
		return display(source, winterfestData)
	}

	class WinterfestState(val winterfestData: FortWinterfestData, val profile: McpProfile) {
		val rewardGraphItem = profile.items.values.firstOrNull { it.templateId == winterfestData.WinterfestItemTemplateId }
			?: throw SimpleCommandExceptionType(LiteralMessage("No Winterfest right now.")).create()
		val rewardGraphDef = rewardGraphItem.defData as AthenaRewardEventGraph
		val graphAttrs = rewardGraphItem.getAttributes(AthenaRewardEventGraphItem::class.java)
		val dayNumber = 1 + (System.currentTimeMillis() - graphAttrs.unlock_epoch.time) / (24 * 60 * 60 * 1000)
		val keyItem = profile.items.values.firstOrNull { it.templateId == winterfestData.WinterfestKeyTemplateId } ?: FortItemStack(winterfestData.WinterfestKeyTemplateId, 0)
		val unusedKeys = keyItem.quantity - (graphAttrs.reward_keys?.getOrNull(0)?.unlock_keys_used ?: 0)
		val nextKeyAt = if (keyItem.quantity < rewardGraphDef.RewardKey.first().RewardKeyMaxCount) graphAttrs.unlock_epoch.time + (24 * 60 * 60 * 1000) * dayNumber else -1
		val rewards = hashMapOf<String, WinterfestReward>()
		val sortedRewards get() = rewards.values.sorted()

		init {
			rewardGraphDef.Rewards.forEach {
				// Skip A1 because I don't know what that does
				if (it.NodeTag.toString() == "ERG.Node.A.1") return@forEach
				rewards[it.NodeTag.toString()] = WinterfestReward(it, this)
			}
		}
	}

	class WinterfestReward(val rewardNode: AthenaRewardEventGraph.RewardNode, val state: WinterfestState) : Comparable<WinterfestReward> {
		val column: Char
		val row: Int

		init {
			// Format: ERG.Node.A.1
			val nodeTag = rewardNode.NodeTag.toString()
			val nodeTagParts = nodeTag.split(".")
			column = nodeTagParts[2][0]
			row = nodeTagParts[3].toInt()
		}

		val isClaimed = state.graphAttrs.reward_nodes_claimed?.contains(rewardNode.NodeTag.toString()) ?: false

		fun getUnclaimedPrerequisites(outUnclaimedNodes: MutableList<WinterfestReward>? = null, recursive: Boolean = false) {
			// In order to be claimable, all child nodes must have been claimed
			rewardNode.ChildNodes.forEach { nodeTag ->
				val childNode = state.rewards[nodeTag.toString()]!!
				if (!childNode.isClaimed) {
					outUnclaimedNodes?.add(childNode)
					if (recursive) {
						childNode.getUnclaimedPrerequisites(outUnclaimedNodes, true)
					}
				}
			}
		}

		val isClaimable: Boolean get() {
			if (isClaimed) {
				return false
			}
			if (state.keyItem.quantity < rewardNode.MinKeyCountToUnlock || state.unusedKeys < rewardNode.KeyCount) {
				return false
			}
			if (rewardNode.ChildNodes.gameplayTags.isNotEmpty()) {
				val unclaimedNodes = mutableListOf<WinterfestReward>()
				getUnclaimedPrerequisites(unclaimedNodes)
				return unclaimedNodes.isEmpty()
			}
			return true
		}

		val displayName get() = "Present $column$row"

		val description: String get() {
			val ribbonColor = when (rewardNode.getVariant("Cosmetics.Variant.Channel.Winterfest.RibbonColor", "Mat1")) {
				"Mat1" -> "Red"
				"Mat2" -> "Blue"
				"Mat3" -> "Green"
				"Mat5" -> "White"
				"Mat6" -> "Yellow"
				"Mat7" -> "Dark Blue"
				else -> "???"
			}
			val wrappingColor = when (rewardNode.getVariant("Cosmetics.Variant.Channel.Winterfest.WrappingColor", "Mat1")) {
				"Mat1" -> "Red"
				"Mat2" -> "Green"
				"Mat3" -> "Blue"
				"Mat4" -> "Yellow"
				"Mat5" -> "Purple"
				"Mat6" -> "Light Blue"
				else -> "???"
			}
			val wrappingStyle = when (rewardNode.getVariant("Cosmetics.Variant.Channel.Winterfest.WrappingStyle", "Mat1")) {
				"Mat1" -> "Trees"
				"Mat2" -> "Snowflakes"
				"Mat3" -> "Candy"
				else -> "???"
			}
			return "$ribbonColor ribbon, $wrappingColor $wrappingStyle wrap"
		}

		private fun AthenaRewardEventGraph.RewardNode.getVariant(variantChannelTag: String, default: String) =
			HardDefinedVisuals.firstOrNull { it.VariantChannelTag.toString() == variantChannelTag }?.ActiveVariantTag?.toString()?.substringAfterLast('.') ?: default

		val longDisplayName get() = "%s (%s)".format(displayName, description)

		fun addTo(embed: EmbedBuilder) {
			val sb = StringBuilder('_' + description + '_')
			rewardNode.Rewards.joinTo(sb, "\n", "\n") { it.safeRender() }
			val unclaimedPrerequisites = mutableListOf<WinterfestReward>()
			getUnclaimedPrerequisites(unclaimedPrerequisites)
			if (unclaimedPrerequisites.isNotEmpty()) {
				sb.append("\n⛔ ")
				unclaimedPrerequisites.joinTo(sb) { it.column.toString() + it.row }
			}
			embed.addField(if (isClaimed) "✅ ~~$displayName~~" else displayName, sb.toString(), true)
		}

		override fun compareTo(other: WinterfestReward) = when {
			column < other.column -> -1
			column > other.column -> 1
			row < other.row -> -1
			row > other.row -> 1
			else -> 0
		}
	}
}