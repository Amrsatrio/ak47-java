package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.L10N
import com.tb24.discordbot.Rune
import com.tb24.discordbot.commands.arguments.ItemArgument.Companion.getItem
import com.tb24.discordbot.commands.arguments.ItemArgument.Companion.item
import com.tb24.discordbot.item.GameplayTagPredicate
import com.tb24.discordbot.ui.ExpeditionBuildSquadViewController
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.FortExpeditionItem
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.asItemStack
import com.tb24.fn.util.format
import me.fungames.jfortniteparse.fort.exports.FortExpeditionItemDefinition
import me.fungames.jfortniteparse.fort.objects.rows.Recipe
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTagContainer
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
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
				val ctx = ExpeditionBuildSquadViewController(expedition, source.session.getHomebase(source.api.currentLoggedIn.id))
				prepare(source, ctx)
			}
			.then(argument("expedition", item(true))
				.executes {
					val source = it.source
					source.ensureSession()
					source.loading("Finding available expeditions")
					source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
					val expedition = getItem(it, "expedition", source.api.profileManager.getProfileData("campaign"), "Expedition")
					val ctx = ExpeditionBuildSquadViewController(expedition, source.session.getHomebase(source.api.currentLoggedIn.id))
					prepare(source, ctx)
				}
			)
		)
		.then(literal("claim")
			.executes { c ->
				val source = c.source
				source.ensureSession()
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
				claim(source, source.api.profileManager.getProfileData("campaign").items.values.first { it.primaryAssetType == "Expedition" })
			}
		)

	private inline fun overview(source: CommandSourceStack, campaign: McpProfile): Int {
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

	private fun prepare(source: CommandSourceStack, ctx: ExpeditionBuildSquadViewController): Int {
		val survivorEmote = textureEmote("/Game/UI/Foundation/Textures/Icons/ItemTypes/T-Icon-Survivor-128.T-Icon-Survivor-128")!!
		val typeIconPath = expeditionTypeIcon(ctx.type)
		val typeEmote = textureEmote(typeIconPath)!!
		val requirements = mutableListOf<String>()
		requirements.add(typeEmote.asMention + ' ' + expeditionTypeTitle(ctx.type).format())
		ctx.recipe.RecipeCosts.mapTo(requirements) { it.asItemStack().renderWithIcon() }
		val embed = source.createEmbed()
			.setTitle(ctx.expedition.displayName)
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
		val buttons = mutableListOf<Button>()
		buttons.add(Button.of(ButtonStyle.PRIMARY, "start", "Start Expedition", Emoji.fromUnicode("✅")))
		buttons.add(Button.of(ButtonStyle.PRIMARY, "changeSlot", "Change Slot", Emoji.fromEmote(survivorEmote)))
		buttons.add(Button.of(ButtonStyle.PRIMARY, "changeVehicle", "Change Vehicle", Emoji.fromEmote(typeEmote)))
		val message = source.complete(null, embed.build(), ActionRow.of(buttons))
		return when (message.awaitOneInteraction(source.author, false, 120000L).componentId) {
			"start" -> start(source, ctx)
			"changeSlot" -> changeSlot(source, ctx)
			"changeVehicle" -> changeVehicle(source, ctx)
			else -> Command.SINGLE_SUCCESS
		}
	}

	private fun start(source: CommandSourceStack, ctx: ExpeditionBuildSquadViewController): Int {
		TODO()
	}

	private fun changeSlot(source: CommandSourceStack, ctx: ExpeditionBuildSquadViewController): Int {
		TODO()
	}

	private fun changeVehicle(source: CommandSourceStack, ctx: ExpeditionBuildSquadViewController): Int {
		TODO()
	}

	private fun claim(source: CommandSourceStack, expedition: FortItemStack): Int {
		val defData = expedition.defData as FortExpeditionItemDefinition
		val attrs = expedition.getAttributes(FortExpeditionItem::class.java)
		attrs.expedition_success_chance = .5f // TODO dummy data
		val recipe = defData.ExpeditionRules.getRowMapped<Recipe>()!!
		val embed = source.createEmbed().setTitle("AN EXPEDITION HAS RETURNED!").setColor(COLOR_WARNING)
			.setDescription("**%s**\n%s Chance of Success".format(
				expedition.displayName,
				Formatters.percentZeroFraction.format(attrs.expedition_success_chance)
			))
			.setThumbnail(Utils.benBotExportAsset(expeditionTypeIcon(recipe.RequiredCatalysts.first().toString())))
		val message = source.complete(null, embed.build(), ActionRow.of(Button.primary("continue", "Continue")))
		val choice = message.awaitOneInteraction(source.author, false).componentId
		if (choice != "continue") {
			return Command.SINGLE_SUCCESS
		}
		// TODO send claim request here
		val succeeded = Math.random() <= attrs.expedition_success_chance
		if (succeeded) {
			embed.setDescription("**%s**\n%s".format(expedition.displayName, "SUCCESS!"))
			embed.setColor(COLOR_SUCCESS)
			message.editMessageEmbeds(embed.build()).complete()
			embed.addField("You received", "Fizz\nBuzz\nFoo\nBar", false)
		} else {
			embed.setDescription("**%s**".format(expedition.displayName))
			embed.addField("Expedition Failed", "Sending powerful heroes increases your chances of a successful expedition", false)
			embed.setColor(COLOR_ERROR)
		}
		message.editMessageEmbeds(embed.build()).complete()
		return Command.SINGLE_SUCCESS
	}

	private fun render(expedition: FortItemStack): String {
		val defData = expedition.defData as FortExpeditionItemDefinition
		val attrs = expedition.getAttributes(FortExpeditionItem::class.java)
		val recipe = defData.ExpeditionRules.getRowMapped<Recipe>()!!
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

	private class ExpeditionTagPredicate(matchGameplayTag: String) : GameplayTagPredicate(matchGameplayTag) {
		override fun getGameplayTags(input: FortItemStack): FGameplayTagContainer? {
			val defData = input.defData as FortExpeditionItemDefinition
			return defData.ExpeditionRules.getRowMapped<Recipe>()?.RequiredCatalysts
		}
	}
}