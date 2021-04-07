package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.L10N
import com.tb24.discordbot.Rune
import com.tb24.discordbot.commands.arguments.ItemArgument.Companion.getItem
import com.tb24.discordbot.commands.arguments.ItemArgument.Companion.item
import com.tb24.discordbot.item.GameplayTagPredicate
import com.tb24.discordbot.managers.HomebaseManager
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.StartExpedition
import com.tb24.fn.model.mcpprofile.item.FortExpeditionItem
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.asItemStack
import com.tb24.fn.util.format
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortExpeditionItemDefinition
import me.fungames.jfortniteparse.fort.objects.rows.FortCriteriaRequirementData
import me.fungames.jfortniteparse.fort.objects.rows.Recipe
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTagContainer
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.MessageReaction
import net.dv8tion.jda.api.entities.User
import java.util.*
import kotlin.math.max
import kotlin.math.min

class ExpeditionsCommand : BrigadierCommand("expeditions", "Manages your expeditions.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::isBotDev)
		.withPublicProfile(::overview, "Getting expeditions")
		.then(literal("prepare")
			.executes { c ->
				val source = c.source
				source.ensureSession()
				source.loading("Finding available expeditions")
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
				// TODO interactive expedition picker
				val expedition = source.api.profileManager.getProfileData("campaign").items.values.first { it.primaryAssetType == "Expedition" }
				prepare(source, expedition)
			}
			.then(argument("expedition", item(true, "Expedition"))
				.executes {
					val source = it.source
					source.ensureSession()
					source.loading("Finding available expeditions")
					source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
					prepare(source, getItem(it, "expedition", source.api.profileManager.getProfileData("campaign")))
				}
			)
		)
		.then(literal("claim").executes {
			it.source.ensureSession()
			it.source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
			claim(it.source, it.source.api.profileManager.getProfileData("campaign").items.values.first { it.primaryAssetType == "Expedition" })
		})

	private inline fun overview(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		val embed = source.createEmbed(campaign.owner).setTitle("Expeditions")
		val landAvailable = 2
		val seaAvailable = 2
		val airAvailable = 2
		val totalAvailable = landAvailable + seaAvailable + airAvailable
		val ongoingExpeditions = campaign.items.values.filter { it.primaryAssetType == "Expedition" && it.getAttributes(FortExpeditionItem::class.java).expedition_start_time != null }
		val completedExpeditions = ongoingExpeditions.filter { System.currentTimeMillis() >= it.getAttributes(FortExpeditionItem::class.java).expedition_end_time.time }
		embed.appendDescription("%s %,d / %,d - %s %,d / %,d - %s %,d / %,d".format(
			textureEmote("/Game/UI/Foundation/Textures/Icons/SkillTree/T-Icon-ST-Struck-128.T-Icon-ST-Struck-128")?.asMention, landAvailable, 2,
			textureEmote("/Game/UI/Foundation/Textures/Icons/SkillTree/T-Icon-ST-Speedboat-128.T-Icon-ST-Speedboat-128")?.asMention, seaAvailable, 2,
			textureEmote("/Game/UI/Foundation/Textures/Icons/SkillTree/T-Icon-ST-Helicopter-128.T-Icon-ST-Helicopter-128")?.asMention, airAvailable, 2))
		if (totalAvailable > 0) {
			embed.appendDescription("\n%,d Available Expeditions!".format(totalAvailable))
		}
		if (completedExpeditions.isNotEmpty()) {
			embed.appendDescription("\n%,d Completed!".format(completedExpeditions.size))
		}
		/*if (campaign.owner == source.api.currentLoggedIn) {
			embed.setDescription("`%s%s prepare` - Prepare an expedition\n`%s%s prepare` - Prepare an expedition".format(source.prefix, c.commandName))
		}*/
		embed.addField("In progress", if (ongoingExpeditions.isNotEmpty()) ongoingExpeditions.joinToString("\n\n", transform = ::render) else "No expeditions are in-progress.", false)
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun prepare(source: CommandSourceStack, expedition: FortItemStack): Int {
		val ctx = ExpeditionBuildSquadContext(expedition, source.session.getHomebase(source.api.currentLoggedIn.id))
		val survivorEmote = textureEmote("/Game/UI/Foundation/Textures/Icons/ItemTypes/T-Icon-Survivor-128.T-Icon-Survivor-128")!!
		val typeIconPath = expeditionTypeIcon(ctx.type)
		val typeEmote = textureEmote(typeIconPath)!!
		val requirements = mutableListOf<String>()
		requirements.add(typeEmote.asMention + ' ' + expeditionTypeTitle(ctx.type).format())
		ctx.recipe.RecipeCosts.mapTo(requirements) { it.asItemStack().renderWithIcon() }
		val embed = source.createEmbed()
			.setTitle(expedition.displayName)
			.setThumbnail(Utils.benBotExportAsset(typeIconPath))
			.setDescription("**Target Squad Power: %,d**\nDuration: %s\n%s\n\n✅ Start Expedition\n%s Change Slot\n%s Change Vehicle".format(
				ctx.attrs.expedition_max_target_power,
				StringUtil.formatElapsedTime((ctx.defData.ExpeditionDuration_Minutes * 60 * 1000).toLong(), false),
				ctx.defData.Description.format(),
				survivorEmote.asMention,
				typeEmote.asMention))
			.setFooter("Expires")
			.setTimestamp(ctx.attrs.expedition_expiration_end_time.toInstant())
			.addField("Requirements", requirements.joinToString("\n"), true)
			.addField("Slot Bonuses", if (ctx.criteriaRequirements.isNotEmpty()) ctx.criteriaRequirements.joinToString("\n") { criteriaRequirement ->
				textureEmote(heroTypeIcon(criteriaRequirement.RequiredTag.toString()))?.asMention + ' ' + (if (criteriaRequirement.bRequireRarity) criteriaRequirement.RequiredRarity.rarityName.format() + ' ' else "") + heroTypeTitle(criteriaRequirement.RequiredTag.toString()).format()
			} else "None", true)
			.addField("Rewards", ctx.recipe.RecipeResults.joinToString("\n") { it.asItemStack().renderWithIcon() }, true)
		val message = source.complete(null, embed.build())
		message.addReaction("✅").queue()
		message.addReaction(survivorEmote).queue()
		message.addReaction(typeEmote).queue()
		val collector = message.createReactionCollector({ _, user, _ -> user == source.author }, ReactionCollectorOptions().apply {
			idle = 45000L
		})
		collector.callback = object : CollectorListener<MessageReaction> {
			override fun onCollect(item: MessageReaction, user: User?) {
				try {
					if (message.member?.hasPermission(Permission.MESSAGE_MANAGE) == true) {
						item.removeReaction(source.author).queue()
					}
					/*val emote = item.
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
					message.editMessage(renderEmbed(source, campaignModified, ctx)).queue()*/
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

	private fun claim(source: CommandSourceStack, expedition: FortItemStack): Int {
		val defData = expedition.defData as FortExpeditionItemDefinition
		val attrs = expedition.getAttributes(FortExpeditionItem::class.java)
		attrs.expedition_success_chance = .5f // TODO dummy data
		val recipe = defData.ExpeditionRules.getRowMapped<Recipe>()
		val embed = source.createEmbed().setTitle("AN EXPEDITION HAS RETURNED!").setColor(COLOR_WARNING)
			.setDescription("**%s**\n%s Chance of Success".format(
				expedition.displayName,
				Formatters.percentZeroFraction.format(attrs.expedition_success_chance)
			))
			.setThumbnail(Utils.benBotExportAsset(expeditionTypeIcon(recipe.RequiredCatalysts.first().toString())))
		val message = source.complete(null, embed.build())
		message.addReaction("✅").queue()
		if (message.awaitReactions({ _, user, _ -> user?.idLong == source.message.author.idLong }, AwaitReactionsOptions().apply {
				max = 1
				time = 30000L
			}).await().isEmpty()) {
			return Command.SINGLE_SUCCESS
		}
		// TODO send claim request here
		val succeeded = Math.random() <= attrs.expedition_success_chance
		if (succeeded) {
			embed.setDescription("**%s**\n%s".format(expedition.displayName, "SUCCESS!"))
			embed.setColor(COLOR_SUCCESS)
			message.editMessage(embed.build()).complete()
			embed.addField("You received", "Fizz\nBuzz\nFoo\nBar", false)
		} else {
			embed.setDescription("**%s**".format(expedition.displayName))
			embed.addField("Expedition Failed", "Sending powerful heroes increases your chances of a successful expedition", false)
			embed.setColor(COLOR_ERROR)
		}
		message.editMessage(embed.build()).complete()
		return Command.SINGLE_SUCCESS
	}

	private fun render(expedition: FortItemStack): String {
		val defData = expedition.defData as FortExpeditionItemDefinition
		val attrs = expedition.getAttributes(FortExpeditionItem::class.java)
		val recipe = defData.ExpeditionRules.getRowMapped<Recipe>()
		val startTime = attrs.expedition_start_time.time
		val max = attrs.expedition_end_time.time - startTime
		val progress = min(System.currentTimeMillis() - startTime, max)
		return "[%,d] **%s**\n%s **%s**\n%s\n%s %s".format(
			attrs.expedition_max_target_power,
			expedition.displayName,
			textureEmote(expeditionTypeIcon(recipe.RequiredCatalysts.first().toString())),
			Formatters.percentZeroFraction.format(attrs.expedition_success_chance),
			if (System.currentTimeMillis() >= attrs.expedition_end_time.time) "Completed!" else "Returns: " + StringUtil.formatElapsedTime(max(0L, attrs.expedition_end_time.time - System.currentTimeMillis()), true),
			Utils.progress(progress.toInt(), max.toInt(), 32),
			Formatters.percentZeroFraction.format(progress.toFloat() / max.toFloat())
		)
	}

	private fun expeditionTypeIcon(tag: String) = when (tag) {
		"Expedition.Land" -> "/Game/UI/Foundation/Textures/Icons/SkillTree/T-Icon-ST-Struck-128.T-Icon-ST-Struck-128"
		"Expedition.Sea" -> "/Game/UI/Foundation/Textures/Icons/SkillTree/T-Icon-ST-Speedboat-128.T-Icon-ST-Speedboat-128"
		"Expedition.Air" -> "/Game/UI/Foundation/Textures/Icons/SkillTree/T-Icon-ST-Helicopter-128.T-Icon-ST-Helicopter-128"
		else -> "/Game/UI/Foundation/Textures/Icons/Mission/T-Icon-Unknown-128.T-Icon-Unknown-128"
	}

	private fun expeditionTypeTitle(tag: String) = when (tag) {
		"Expedition.Land" -> L10N.E_FILTER_LAND
		"Expedition.Sea" -> L10N.E_FILTER_SEA
		"Expedition.Air" -> L10N.E_FILTER_AIR
		else -> FText("???")
	}

	private fun heroTypeIcon(tag: String) = when (tag) {
		"Homebase.Class.IsCommando" -> "/Game/UI/Foundation/Textures/Icons/ItemTypes/T-Icon-Hero-Soldier-128.T-Icon-Hero-Soldier-128"
		"Homebase.Class.IsConstructor" -> "/Game/UI/Foundation/Textures/Icons/ItemTypes/T-Icon-Hero-Constructor-128.T-Icon-Hero-Constructor-128"
		"Homebase.Class.IsNinja" -> "/Game/UI/Foundation/Textures/Icons/ItemTypes/T-Icon-Hero-Ninja-128.T-Icon-Hero-Ninja-128"
		"Homebase.Class.IsOutlander" -> "/Game/UI/Foundation/Textures/Icons/ItemTypes/T-Icon-Hero-Outlander-128.T-Icon-Hero-Outlander-128"
		else -> "/Game/UI/Foundation/Textures/Icons/Mission/T-Icon-Unknown-128.T-Icon-Unknown-128"
	}

	private fun heroTypeTitle(tag: String) = when (tag) {
		"Homebase.Class.IsCommando" -> FText("", "6A29086E42996FD28FE84085BF9FA001", "Soldier")
		"Homebase.Class.IsConstructor" -> FText("", "CD93D49543A424261B9906B4DFBCEC62", "Constructor")
		"Homebase.Class.IsNinja" -> FText("", "1016C3544CD42273AAD330B608A2D55F", "Ninja")
		"Homebase.Class.IsOutlander" -> FText("", "40A39DC04A7B281E3B0D31844EA6C6AC", "Outlander")
		else -> FText("???")
	}

	private class ExpeditionBuildSquadContext(val expedition: FortItemStack, homebase: HomebaseManager) {
		val attrs = expedition.getAttributes(FortExpeditionItem::class.java)
		val defData = expedition.defData as FortExpeditionItemDefinition
		val recipe = defData.ExpeditionRules.getRowMapped<Recipe>()
		val type = recipe.RequiredCatalysts.first().toString()
		val criteriaRequirements by lazy {
			val criteriaRequirementsTable = loadObject<UDataTable>("/SaveTheWorld/Expeditions/CriteriaRequirements/ExpeditionCriteriaRequirements")!!
			attrs.expedition_criteria.map { criteriaRequirementsTable.findRowMapped<FortCriteriaRequirementData>(FName.dummy(it))!! }
		}
		val squadChoices = mutableListOf<HomebaseManager.Squad>()
		var squadChoiceIndex = 0
		val squad get() = squadChoices[squadChoiceIndex]

		init {
			val squadIds = when (type) {
				"Expedition.Land" -> arrayOf("Squad_Expedition_ExpeditionSquadOne", "Squad_Expedition_ExpeditionSquadTwo")
				"Expedition.Sea" -> arrayOf("Squad_Expedition_ExpeditionSquadThree", "Squad_Expedition_ExpeditionSquadFour")
				"Expedition.Air" -> arrayOf("Squad_Expedition_ExpeditionSquadFive", "Squad_Expedition_ExpeditionSquadSix")
				else -> throw AssertionError()
			}
			for (squadId in squadIds) {
				val squad = homebase.squads[squadId.toLowerCase(Locale.ROOT)]!!
				val unlockedStates = BooleanArray(squad.slots.size)
				var hasUnlockedSlot = false
				var occupied = false
				squad.slots.forEachIndexed { i, slot ->
					unlockedStates[i] = slot.unlocked
					hasUnlockedSlot = hasUnlockedSlot || slot.unlocked
					occupied = occupied || slot.item != null
				}
				if (hasUnlockedSlot && !occupied) {
					val clonedSquad = HomebaseManager.Squad(squadId, squad.backing)
					clonedSquad.slots.forEachIndexed { i, slot ->
						slot.unlocked = unlockedStates[i]
					}
					squadChoices.add(clonedSquad)
				}
			}
		}

		fun generatePayload(): StartExpedition {
			val itemIds = mutableListOf<String>()
			val slotIndices = mutableListOf<Int>()
			var i = 0
			squad.slots.forEach {
				val item = it.item
				if (item != null) {
					itemIds.add(item.itemId)
					slotIndices.add(i++)
				}
			}
			val payload = StartExpedition()
			payload.expeditionId = expedition.itemId
			payload.squadId = squad.squadId
			payload.itemIds = itemIds.toTypedArray()
			payload.slotIndices = slotIndices.toIntArray()
			return payload
		}
	}

	private class ExpeditionTagPredicate(matchGameplayTag: String) : GameplayTagPredicate(matchGameplayTag) {
		override fun getGameplayTags(input: FortItemStack): FGameplayTagContainer? {
			val defData = input.defData as FortExpeditionItemDefinition
			return defData.ExpeditionRules.getRowMapped<Recipe>()?.RequiredCatalysts
		}
	}
}