package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB
import com.tb24.discordbot.commands.arguments.StringArgument2
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.campaign.AssignGadgetToLoadout
import com.tb24.fn.model.mcpprofile.commands.campaign.AssignHeroToLoadout
import com.tb24.fn.model.mcpprofile.commands.campaign.AssignTeamPerkToLoadout
import com.tb24.fn.model.mcpprofile.commands.campaign.SetActiveHeroLoadout
import com.tb24.fn.model.mcpprofile.item.FortCampaignHeroLoadoutItem
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.fn.util.format
import com.tb24.fn.util.getPreviewImagePath
import me.fungames.jfortniteparse.fort.exports.FortAbilityKit
import me.fungames.jfortniteparse.fort.exports.FortHeroGameplayDefinition
import me.fungames.jfortniteparse.fort.exports.FortHeroType
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.ComponentInteraction
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import java.util.concurrent.CompletableFuture
import kotlin.math.max

class HeroLoadoutCommand : BrigadierCommand("heroloadout", "Manages your STW hero loadouts.", arrayOf("hl")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting hero loadouts")
		.then(literal("save")
			.then(argument("loadout num", IntegerArgumentType.integer(1, 9))
				.then(argument("name", StringArgument2.string2(true))
					.executes {
						saveLoadout(it.source, IntegerArgumentType.getInteger(it, "loadout num"), StringArgumentType.getString(it, "name"))
					}
				)
			)
		)
		.then(literal("apply")
			.then(argument("loadout num", IntegerArgumentType.integer(1, 9))
				.then(argument("name", StringArgument2.string2(true))
					.executes {
						applyLoadout(it.source, IntegerArgumentType.getInteger(it, "loadout num"), StringArgumentType.getString(it, "name"))
					}
				)
			)
		)
		.then(literal("remove")
			.then(argument("name", StringArgument2.string2(true))
				.executes {
					removeLoadout(it.source, StringArgumentType.getString(it, "name"))
				}
			)
		)
		.then(literal("override")
			.then(argument("loadout num", IntegerArgumentType.integer(1, 9))
				.then(argument("name", StringArgument2.string2(true))
					.executes {
						removeLoadout(it.source, StringArgumentType.getString(it, "name"), true)
						saveLoadout(it.source, IntegerArgumentType.getInteger(it, "loadout num"), StringArgumentType.getString(it, "name"))
					}
				)
			)
		)
		.then(literal("list")
			.executes { listLoadouts(it.source) }
		)

	private fun execute(source: CommandSourceStack, campaign: McpProfile): Int {
		source.ensureCompletedCampaignTutorial(campaign)
		val stats = campaign.stats as CampaignProfileStats
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
		source.replyPaginated(available, 1, max(available.indexOfFirst { it.itemId == stats.selected_hero_loadout }, 0), if (isMine) PaginatorComponents(available, event) else null) { content, page, pageCount ->
			val loadoutItem = content[0]
			val loadoutAttrs = loadoutItem.getAttributes(FortCampaignHeroLoadoutItem::class.java)
			val embed = source.createEmbed(campaign.owner)
				.setTitle("Hero Loadout %,d".format(loadoutAttrs.loadout_index + 1))
				.setFooter("%,d of %,d".format(page + 1, pageCount) + if (loadoutItem.itemId == stats.selected_hero_loadout) " (current)" else "")
			val commanderItem = campaign.items[loadoutAttrs.crew_members["commanderslot"]]
			if (commanderItem != null) {
				embed.addField("Commander", commanderItem.displayName, false)
				embed.setThumbnail(Utils.benBotExportAsset(commanderItem.getPreviewImagePath(true)?.toString()))
			} else {
				embed.addField("Commander", "Empty", false)
			}
			embed.addField("Team Perk", campaign.items[loadoutAttrs.team_perk]?.displayName ?: "Empty", false)
			embed.addField("Support Team", loadoutAttrs.crew_members.toList().sortedBy { it.first }.filterNot { it.first == "commanderslot" || it.second.isEmpty() }.toMap().values.joinToString("\n") {
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
			MessageBuilder(embed.build())
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

	private class PaginatorComponents(val list: List<FortItemStack>, val event: CompletableFuture<FortItemStack?>) : PaginatorCustomComponents<FortItemStack> {
		private var confirmed = false

		override fun modifyComponents(paginator: Paginator<FortItemStack>, rows: MutableList<ActionRow>) {
			rows.add(ActionRow.of(Button.of(ButtonStyle.PRIMARY, "setActive", "Set active", Emoji.fromUnicode("✅"))))
		}

		override fun handleComponent(paginator: Paginator<FortItemStack>, item: ComponentInteraction, user: User?) {
			if (!confirmed && item.componentId == "setActive") {
				confirmed = true
				event.complete(list[paginator.page])
				paginator.stopAndFinalizeComponents(setOf(item.componentId))
			}
		}

		override fun onEnd(collected: Map<Any, ComponentInteraction>, reason: CollectorEndReason) {
			event.complete(null)
		}
	}

	private fun saveLoadout(source: CommandSourceStack, loadoutNum: Int, name: String): Int {
		val loadouts = getLoadoutsList(source)
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = source.api.profileManager.getProfileData("campaign")
		if (loadouts.size < loadoutNum) {
			throw SimpleCommandExceptionType(LiteralMessage("Loadout **%,d** does not exist.".format(loadoutNum))).create()
		}
		val loadout = loadouts[loadoutNum - 1].getAttributes(FortCampaignHeroLoadoutItem::class.java)
		val loadoutEntry = HeroLoadoutEntry().apply {
			this.name = name
			for ((slotName, slotItemId) in loadout.crew_members.filterNot { it.value.isEmpty() || it.key == "leaderslot"}) {
				slots[slotName] = HeroLoadoutEntry.HeroItem().apply {
					itemId = slotItemId
					templateId = campaign.items[slotItemId]?.templateId
				}
			}
			teamPerkId = loadout.team_perk
			gadgets = loadout.gadgets.map {
				HeroLoadoutEntry.GadgetItem().apply {
					gadget = it.gadget
					slot_index = it.slot_index
				}
			}
		}
		if (!addToDb(source, loadoutEntry)) {
			throw SimpleCommandExceptionType(LiteralMessage("Loadout with this name already exists.")).create()
		}
		source.complete(null, source.createEmbed().setDescription("✅ Saved loadout **%,d** as **%s**".format(loadoutNum, name)).build())
		return Command.SINGLE_SUCCESS
	}

	private fun applyLoadout(source: CommandSourceStack, loadoutNum: Int, name: String): Int {
		source.ensureSession()
		val savedLoadouts = RethinkDB.r.table("hero_loadouts").get(source.api.currentLoggedIn.id).run(source.client.dbConn, dbEntry::class.java).first()
			?: throw SimpleCommandExceptionType(LiteralMessage("You have no saved loadouts.")).create()
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = source.api.profileManager.getProfileData("campaign")
		val loadoutToApply = savedLoadouts.loadouts!!.find { it.name == name}
			?: throw SimpleCommandExceptionType(LiteralMessage("Loadout **%s** not found.".format(name))).create()
		val loadout = campaign.items.values.find {
			it.templateId == "CampaignHeroLoadout:defaultloadout" && it.getAttributes(FortCampaignHeroLoadoutItem::class.java).loadout_index == loadoutNum -1
		} ?: throw SimpleCommandExceptionType(LiteralMessage("Loadout **%d** does not exist.".format(loadoutNum))).create()
		for (slot in loadoutToApply.slots.entries.filterNot { it.value.templateId == "" || it.key == "leaderslot"}) {
			val item = campaign.items[slot.value.itemId] ?: campaign.items.values.filter {
				it.templateId.substringBeforeLast("_").substringBeforeLast("_") == slot.value.templateId?.substringBeforeLast("_")?.substringBeforeLast("_")
			}.maxByOrNull { it.powerLevel }
			if (item == null) {
				source.channel.sendMessage("Couldn't find a replacement for %s: %s. Please change the hero and override the loadout.".format(slot.key, slot.value.templateId)).queue()
				continue
			}
			source.api.profileManager.dispatchClientCommandRequest(AssignHeroToLoadout().apply {
				heroId = item.itemId
				loadoutId = loadout.itemId
				slotName = slot.key
			}, "campaign")
		}
		source.api.profileManager.dispatchClientCommandRequest(AssignTeamPerkToLoadout().apply {
			teamPerkId = loadoutToApply.teamPerkId!!
			loadoutId = loadout.itemId
		}, "campaign")
		if (loadoutToApply.gadgets != null) {
			for (gadget in loadoutToApply.gadgets!!) {
				source.api.profileManager.dispatchClientCommandRequest(AssignGadgetToLoadout().apply {
					gadgetId = gadget.gadget
					loadoutId = loadout.itemId
					slotIndex = gadget.slot_index
				}, "campaign")
			}
		}
		source.complete(null, source.createEmbed().setDescription("✅ Applied **%s** to loadout **%,d**".format(name, loadoutNum)).build())
		return Command.SINGLE_SUCCESS
	}

	private fun removeLoadout(source: CommandSourceStack, name: String, silent: Boolean = false): Int {
		source.ensureSession()
		val dbEntry = RethinkDB.r.table("hero_loadouts").get(source.api.currentLoggedIn.id).run(source.client.dbConn, dbEntry::class.java).first()
			?: throw SimpleCommandExceptionType(LiteralMessage("You have no saved loadouts.")).create()
		if (!dbEntry.loadouts!!.any { it.name == name}) throw SimpleCommandExceptionType(LiteralMessage("You don't have a saved loadout with that name.")).create()
		val filtered = dbEntry.loadouts!!.filter { it.name != name }
		if (filtered.isNotEmpty()) {
			RethinkDB.r.table("hero_loadouts").update(dbEntry().apply {
				id = source.api.currentLoggedIn.id
				loadouts = filtered.toMutableList()
			})
		} else {
			RethinkDB.r.table("hero_loadouts").get(source.api.currentLoggedIn.id).delete()
		}.run(source.client.dbConn)
		if (!silent) {
			source.complete(null, source.createEmbed().setDescription("✅ Removed **%s**".format(name)).build())
		}
		return Command.SINGLE_SUCCESS
	}

	private fun listLoadouts(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting saved loadouts")
		val savedLoadouts = RethinkDB.r.table("hero_loadouts").get(source.api.currentLoggedIn.id).run(source.client.dbConn, dbEntry::class.java).first() ?:
		throw SimpleCommandExceptionType(LiteralMessage("No saved loadouts.")).create()
		val names = List(savedLoadouts.loadouts!!.size) { i -> savedLoadouts.loadouts!![i].name }
		source.complete(null, source.createEmbed().setTitle("Saved loadouts").setDescription(names.joinToString(", ")).build())
		return Command.SINGLE_SUCCESS
	}

	private fun getLoadoutsList(source: CommandSourceStack): List<FortItemStack> {
		source.ensureSession()
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = source.api.profileManager.getProfileData("campaign")
		source.ensureCompletedCampaignTutorial(campaign)
		val loadoutsMap = sortedMapOf<Int, FortItemStack>()
		for (item in campaign.items.values) {
			if (item.primaryAssetType != "CampaignHeroLoadout") {
				continue
			}
			val loadoutAttrs = item.getAttributes(FortCampaignHeroLoadoutItem::class.java)
			loadoutsMap[loadoutAttrs.loadout_index] = item
		}
		return loadoutsMap.values.toList()
	}

	private fun addToDb(source: CommandSourceStack, loadout: HeroLoadoutEntry): Boolean {
		val dbEntry = RethinkDB.r.table("hero_loadouts").get(source.api.currentLoggedIn.id).run(source.client.dbConn, dbEntry::class.java).first()
		val loadouts = dbEntry?.loadouts ?: mutableListOf()
		if (loadouts.firstOrNull { it.name == loadout.name } != null) {
			return false // already exists
		}
		loadouts.add(loadout)
		val newContents = dbEntry()
		newContents.id = source.api.currentLoggedIn.id
		newContents.loadouts = loadouts
		if (dbEntry != null) {
			RethinkDB.r.table("hero_loadouts").update(newContents)
		} else {
			RethinkDB.r.table("hero_loadouts").insert(newContents)
		}.run(source.client.dbConn)
		return true
	}

	class HeroLoadoutEntry {
		var name: String? = null
		var slots = mutableMapOf<String, HeroItem>()
		var teamPerkId: String? = null
		var gadgets: List<GadgetItem>? = null

		class HeroItem {
			var itemId: String? = null
			var templateId: String? = null
		}

		class GadgetItem {
			var gadget: String? = null
			var slot_index: Int? = null
		}
	}
	class dbEntry {
		var id: String? = null
		var loadouts: MutableList<HeroLoadoutEntry>? = null
	}
}