package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB
import com.tb24.discordbot.commands.arguments.StringArgument2
import com.tb24.discordbot.item.ItemTypeResolver
import com.tb24.discordbot.managers.HomebaseManager
import com.tb24.discordbot.managers.managerData
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.AssignWorkerToSquadBatch
import com.tb24.fn.model.mcpprofile.commands.campaign.UnassignAllSquads
import com.tb24.fn.util.Utils
import com.tb24.fn.util.format
import com.tb24.fn.util.getPathName
import me.fungames.jfortniteparse.fort.enums.EFortStatType
import me.fungames.jfortniteparse.fort.objects.rows.HomebaseSquad.ESquadSlotType
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.emoji.Emoji

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
			val nothing = getEmoteByName("nothing")?.formatted ?: ""
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

class WorkerSquadsCommand : BrigadierCommand("survivorsquads", "Shows your or a given player's survivor squads.", arrayOf("squads", "sq")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting survivor squads")
		.then(literal("save")
			.then(argument("name", StringArgument2.string2(true))
				.executes { saveSquads(it.source, StringArgumentType.getString(it, "name")) }
			)
		)
		.then(literal("apply")
			.then(argument("name", StringArgument2.string2(true))
				.executes { applySquads(it.source, StringArgumentType.getString(it, "name")) }
			)
		)
		.then(literal("remove")
			.then(argument("name", StringArgument2.string2(true))
				.executes { removeSquads(it.source, StringArgumentType.getString(it, "name")) }
			)
		)
		.then(literal("override")
			.then(argument("name", StringArgument2.string2(true))
				.executes {
					removeSquads(it.source, StringArgumentType.getString(it, "name"), true)
					saveSquads(it.source, StringArgumentType.getString(it, "name"))
				}
			)
		)
		.then(literal("list")
			.executes {
				listSquads(it.source)
			}
		)
		.then(literal("clear")
			.executes {
				val source = it.source
				if (!source.complete(null, source.createEmbed().setColor(COLOR_WARNING)
						.setTitle("✋ Hold up!")
						.setDescription("This will clear **ALL** survivor squads.\n\nContinue?")
						.build(), confirmationButtons()).awaitConfirmation(source).await()) {
					source.complete("👌 Alright.")
					return@executes Command.SINGLE_SUCCESS
				}
				source.api.profileManager.dispatchClientCommandRequest(UnassignAllSquads().apply {
					squadIds = SURVIVOR_SQUAD_NAMES
				}, "campaign").await()
				source.complete(null, source.createEmbed().setDescription("✅ Cleared all survivor squads.").build())
				Command.SINGLE_SUCCESS
			}
		)

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
				.setTitle(synergyCategoryIcon?.formatted + ' ' + squad.backing.DisplayName)
				.setDescription("%s +%,d (+%,d team)".format(textureEmote(statType.icon)?.formatted, squad.getStatBonus(statType), squad.getStatBonus(statTypeTeam)))
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
				embed.addField(slot.backing.DisplayName.format().orEmpty(), "%s +%,d".format(itemText, slot.bonuses.values.sum()), slot.backing.SlotType != ESquadSlotType.SurvivorSquadLeadSurvivor)
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
				setBonuses.add("✅" + ' ' + synergyCategoryIcon?.formatted + ' ' + "**Leader Match**")
			} else {
				setBonuses.add("❌" + ' ' + synergyCategoryIcon?.formatted + ' ' + "Leader Match")
			}

			fun simple(icon: Emoji?, title: String?, a: Int, b: Int) = setBonuses.add(if (a < b) {
				"❌ %s %,d/%,d %s".format(icon?.formatted, a, b, title)
			} else {
				"✅ %s **%,d/%,d %s**".format(icon?.formatted, a, b, title)
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

	private fun saveSquads(source: CommandSourceStack, name: String): Int {
		source.ensureSession()
		source.loading("Getting squads")
		val squads = getSquads(source)
		if (!addToDb(source, squads, name)) {
			throw SimpleCommandExceptionType(LiteralMessage("**%s** squads already exist".format(name))).create()
		}
		source.complete(null, source.createEmbed().setDescription("✅ Saved current squads as **%s**".format(name)).build())
		return Command.SINGLE_SUCCESS
	}

	private fun applySquads(source: CommandSourceStack, name: String): Int {
		source.ensureSession()
		source.loading("Applying Survivor squads")
		val savedLoadouts = RethinkDB.r.table("survivor_loadouts").get(source.api.currentLoggedIn.id).run(source.client.dbConn, dbEntry::class.java).first()
			?: throw SimpleCommandExceptionType(LiteralMessage("You have no saved Survivor loadouts.")).create()
		val loadoutToApply = savedLoadouts.squads?.find { it.name == name}
			?: throw SimpleCommandExceptionType(LiteralMessage("Squads loadout **%s** not found.".format(name))).create()
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = source.api.profileManager.getProfileData("campaign")
		val characterIds = mutableListOf<String>()
		val squadIds = mutableListOf<String>()
		val slotIndices = mutableListOf<Int>()
		for (squad in loadoutToApply.squads) {
			squad.value.workers?.forEachIndexed{index, workerItem ->
				if (workerItem.itemId in campaign.items) { // cba adding finding for a replacement
					workerItem.itemId!!.let { characterIds.add(it) }
					squad.key.let { squadIds.add(it) }
					index.let { slotIndices.add(it) }
				}
			}
		}
		source.api.profileManager.dispatchClientCommandRequest(AssignWorkerToSquadBatch().apply {
			this.characterIds = characterIds.toTypedArray()
			this.squadIds = squadIds.toTypedArray()
			this.slotIndices = slotIndices.toIntArray()
		}, "campaign").await()
		source.complete(null, source.createEmbed().setDescription("✅ Applied Squads preset **%s**".format(name)).build())
		return Command.SINGLE_SUCCESS
	}

	private fun getSquads(source: CommandSourceStack): MutableMap<String, dbEntry.WorkerSquad> {
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val homebase = source.session.getHomebase(source.api.currentLoggedIn.id)
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
		val dbSquads = mutableMapOf<String, dbEntry.WorkerSquad>()
		for (squad in squads) {
			dbSquads[squad.squadId] = dbEntry.WorkerSquad().apply {
				workers = squad.slots.map { worker ->
					dbEntry.WorkerItem().apply {
						itemId = worker.item?.itemId
						personality = worker.personality
						setBonus = worker.setBonus
					}
				}
			}
		}
		return dbSquads
	}

	private fun removeSquads(source: CommandSourceStack, name: String, silent: Boolean = false): Int {
		source.ensureSession()
		val dbEntry = RethinkDB.r.table("survivor_loadouts").get(source.api.currentLoggedIn.id).run(source.client.dbConn, dbEntry::class.java).first()
			?: throw SimpleCommandExceptionType(LiteralMessage("You have no saved Surirvor loadouts.")).create()
		if (!dbEntry.squads!!.any { it.name == name}) throw SimpleCommandExceptionType(LiteralMessage("You don't have a saved Surirvor loadout with that name.")).create()
		val filtered = dbEntry.squads!!.filter { it.name != name }
		if (filtered.isNotEmpty()) {
			RethinkDB.r.table("survivor_loadouts").update(dbEntry().apply {
				id = source.api.currentLoggedIn.id
				squads = filtered.toMutableList()
			})
		} else {
			RethinkDB.r.table("survivor_loadouts").get(source.api.currentLoggedIn.id).delete()
		}.run(source.client.dbConn)
		if (!silent) {
			source.complete(null, source.createEmbed().setDescription("✅ Removed **%s**".format(name)).build())
		}
		return Command.SINGLE_SUCCESS
	}

	private fun listSquads(source: CommandSourceStack): Int {
		source.loading("Getting saved loadouts")
		val savedLoadouts = RethinkDB.r.table("survivor_loadouts").get(source.api.currentLoggedIn.id).run(source.client.dbConn, dbEntry::class.java).first() ?:
		throw SimpleCommandExceptionType(LiteralMessage("No saved loadouts.")).create()
		val names = List(savedLoadouts.squads!!.size) { i -> savedLoadouts.squads!![i].name }
		source.complete(null, source.createEmbed().setTitle("Saved Surirvor loadouts").setDescription(names.joinToString(", ")).build())
		return Command.SINGLE_SUCCESS
	}

	private fun addToDb(source: CommandSourceStack, loadout: MutableMap<String, dbEntry.WorkerSquad>, name: String): Boolean {
		val dbEntry = RethinkDB.r.table("survivor_loadouts").get(source.api.currentLoggedIn.id).run(source.client.dbConn, dbEntry::class.java).first()
		val loadouts = dbEntry?.squads ?: mutableListOf()
		if (loadouts.firstOrNull { it.name == name } != null) {
			return false // already exists
		}
		loadouts.add(WorkerSquadsCommand.dbEntry.SquadsEntry().apply {
			this.name = name
			squads = loadout
		})
		val newContents = dbEntry()
		newContents.id = source.api.currentLoggedIn.id
		newContents.squads = loadouts
		if (dbEntry != null) {
			RethinkDB.r.table("survivor_loadouts").update(newContents)
		} else {
			RethinkDB.r.table("survivor_loadouts").insert(newContents)
		}.run(source.client.dbConn)
		return true
	}

	class dbEntry {
		 lateinit var id: String
		 var squads: MutableList<SquadsEntry>? = null

		class SquadsEntry {
			lateinit var name: String
			lateinit var squads: MutableMap<String, WorkerSquad>
		}

		class WorkerSquad {
			@JvmField var workers: List<WorkerItem>? = null
		}

		class WorkerItem {
			var itemId: String? = null
			var personality: String? = null
			var setBonus: String? = null
		}
	}
}

private fun renderWorker(item: FortItemStack, nothing: String = getEmoteByName("nothing")?.formatted ?: ""): String {
	val itemTypeResolver = ItemTypeResolver.resolveItemType(item)
	val dn = item.displayName
	return "%s%s%s%s Lv%,d%s".format(
		getEmoteByName(item.rarity.name.toLowerCase() + '2')?.formatted ?: nothing,
		textureEmote(itemTypeResolver.leftImg)?.formatted ?: nothing,
		textureEmote(itemTypeResolver.middleImg)?.formatted ?: nothing,
		textureEmote(itemTypeResolver.rightImg)?.formatted ?: nothing,
		item.attributes["level"]?.asInt ?: 0,
		if (dn.isNotEmpty()) " $dn" else ""
	)
}
