package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.item.ItemTypeResolver
import com.tb24.discordbot.managers.HomebaseManager
import com.tb24.discordbot.managers.managerData
import com.tb24.discordbot.util.getEmoteByName
import com.tb24.discordbot.util.replyPaginated
import com.tb24.discordbot.util.textureEmote
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.util.Utils
import com.tb24.fn.util.format
import com.tb24.fn.util.getPathName
import me.fungames.jfortniteparse.fort.enums.EFortStatType
import me.fungames.jfortniteparse.fort.objects.rows.HomebaseSquad.ESquadSlotType
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Emote


class WorkersCommand : BrigadierCommand("survivors", "Shows your or a given player's survivors.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting survivors")

	private fun execute(source: CommandSourceStack, campaign: McpProfile): Int {
		source.ensureCompletedCampaignTutorial(campaign)
		val survivors = campaign.items.values.filter { it.primaryAssetType == "Worker" }.sortedWith { a, b ->
			val rating1 = a.powerLevel
			val rating2 = b.powerLevel
			if (rating1 == rating2) {
				a.displayName.compareTo(b.displayName)
			} else {
				rating2.compareTo(rating1)
			}
		}
		source.replyPaginated(survivors, 10) { content, page, pageCount ->
			val embed = source.createEmbed(campaign.owner).setTitle("Survivors")
			val nothing = getEmoteByName("nothing")?.asMention ?: ""
			embed.setDescription(content.joinToString("\n") { item ->
				renderWorker(item, nothing)
			})
			MessageBuilder(embed)
		}

		return Command.SINGLE_SUCCESS
	}
}

val SURVIVOR_SQUAD_NAMES = arrayOf(
	"Squad_Attribute_Medicine_EMTSquad",
	"Squad_Attribute_Arms_FireTeamAlpha",
	"Squad_Attribute_Scavenging_Gadgeteers",
	"Squad_Attribute_Synthesis_CorpsofEngineering",
	"Squad_Attribute_Medicine_TrainingTeam",
	"Squad_Attribute_Arms_CloseAssaultSquad",
	"Squad_Attribute_Scavenging_ScoutingParty",
	"Squad_Attribute_Synthesis_TheThinkTank"
)

class WorkerSquadsCommand : BrigadierCommand("survivorsquads", "Shows your or a given player's survivor squads.", arrayOf("squads")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting survivor squads")

	private fun execute(source: CommandSourceStack, campaign: McpProfile): Int {
		source.ensureCompletedCampaignTutorial(campaign)
		val homebase = source.session.getHomebase(campaign.owner.id)
		val squads = mutableListOf<HomebaseManager.Squad>()
		for (squadId in SURVIVOR_SQUAD_NAMES) {
			val squad = homebase.squads[squadId.toLowerCase()]!!
			val unlockedStates = BooleanArray(squad.slots.size)
			var hasUnlockedSlot = false
			squad.slots.forEachIndexed { i, slot ->
				unlockedStates[i] = slot.unlocked
				hasUnlockedSlot = hasUnlockedSlot || slot.unlocked
			}
			if (hasUnlockedSlot) {
				squads.add(squad)
			}
		}
		source.replyPaginated(squads, 1) { content, page, pageCount ->
			val squad = content.first()
			val statType = EFortStatType.values()[page % 4]
			val statTypeTeam = EFortStatType.values()[4 + page % 4]
			val synergyCategory = ItemTypeResolver.matchCategory(squad.backing.ManagerSynergyTag.toString(), true)
			val synergyCategoryIcon = textureEmote(synergyCategory?.CategoryBrush?.Brush_XL?.ResourceObject?.getPathName())
			val embed = source.createEmbed(campaign.owner)
				.setTitle(synergyCategoryIcon?.asMention + ' ' + squad.backing.DisplayName)
				.setDescription("%s +%,d (+%,d team)".format(textureEmote(statType.icon)?.asMention, squad.getStatBonus(statType), squad.getStatBonus(statTypeTeam)))
				.setFooter("Squad %,d of %,d".format(page + 1, pageCount))

			// Calculate set bonuses
			var personalityMatch = 0
			var unlockedSubordinateSlotsCount = 0
			val setBonusMap = mutableMapOf<String, Int>()
			for (slot in squad.slots) {
				if (!slot.unlocked) {
					continue
				}
				val slotItem = slot.item
				val itemText = slotItem?.let { renderWorker(it, "") } ?: "<Empty>"
				embed.addField(slot.backing.DisplayName.format(), "%s +%,d".format(itemText, slot.bonuses.values.sum()), slot.backing.SlotType != ESquadSlotType.SurvivorSquadLeadSurvivor)
				if (slotItem != null) {
					// set bonuses section preparation
					if (slot.personalityMatch) {
						++personalityMatch;
					}
					if (slot.setBonus != null) {
						Utils.sumKV(setBonusMap, slot.setBonus!!.toLowerCase(), 1);
					}
				}
				if (slot.backing.SlotType == ESquadSlotType.SurvivorSquadSurvivor) {
					++unlockedSubordinateSlotsCount;
				}
			}

			// Build set bonuses section
			val setBonuses = mutableListOf<String>()
			// - Leader Match
			if (squad.managerMatch) {
				setBonuses.add("✅" + ' ' + synergyCategoryIcon?.asMention + ' ' + "**Leader Match**")
			} else {
				setBonuses.add("❌" + ' ' + synergyCategoryIcon?.asMention + ' ' + "Leader Match")
			}

			fun simple(icon: Emote?, title: String?, a: Int, b: Int) = setBonuses.add(if (a < b) {
				"❌ %s %,d/%,d %s".format(icon?.asMention, a, b, title)
			} else {
				"✅ %s **%,d/%,d %s**".format(icon?.asMention, a, b, title)
			})

			// - Personality Match
			squad.managerPersonality?.let { managerPersonality ->
				val personalityCategory = ItemTypeResolver.matchCategory(managerPersonality, false)
				val personalityCategoryIcon = textureEmote(personalityCategory?.CategoryBrush?.Brush_XL?.ResourceObject?.getPathName())
				simple(personalityCategoryIcon, "Personality Match", personalityMatch, unlockedSubordinateSlotsCount)
			}
			// - Set Bonuses
			for ((setBonus, count) in setBonusMap) {
				val setBonusCategory = ItemTypeResolver.matchCategory(setBonus, true)
				val setBonusData = managerData.WorkerSetBonuses.firstOrNull { it.SetBonusTypeTag.toString().equals(setBonus, true) }
				val setBonusIcon = textureEmote(setBonusCategory?.CategoryBrush?.Brush_XL?.ResourceObject?.getPathName())
				simple(setBonusIcon, setBonusCategory!!.CategoryName.format(), count, if (setBonusData != null) setBonusData.RequiredWorkersCount else -1)
			}
			embed.addField("Set bonuses", setBonuses.joinToString("\n"), false)
			MessageBuilder(embed)
		}

		return Command.SINGLE_SUCCESS
	}
}

private fun renderWorker(item: FortItemStack, nothing: String = getEmoteByName("nothing")?.asMention ?: ""): String {
	val itemTypeResolver = ItemTypeResolver.resolveItemType(item)
	val dn = item.displayName
	return "%s%s%s%s %,d%s".format(
		getEmoteByName(item.rarity.name.toLowerCase() + '2')?.asMention ?: nothing,
		textureEmote(itemTypeResolver.leftImg)?.asMention ?: nothing,
		textureEmote(itemTypeResolver.middleImg)?.asMention ?: nothing,
		textureEmote(itemTypeResolver.rightImg)?.asMention ?: nothing,
		item.attributes["level"]?.asInt ?: 0,
		if (dn.isNotEmpty()) " \u2014 $dn" else ""
	)
}
