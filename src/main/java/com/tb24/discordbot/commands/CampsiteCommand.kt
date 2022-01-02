package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.FortCampsiteAccountItem

class CampsiteCommand : BrigadierCommand("tent", "Shows your stored items in your tent.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	override fun getSlashCommand() = newCommandBuilder().executes(::execute)

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting BR data")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val campsite = athena.items.values.firstOrNull { it.templateId == "Campsite:defaultcampsite" }
			?: throw SimpleCommandExceptionType(LiteralMessage("Tent data not found")).create()
		val campsiteData = campsite.getAttributes(FortCampsiteAccountItem::class.java).campsite_account_data
		val embed = source.createEmbed().setThumbnail(Utils.benBotExportAsset("/CampsiteGameplay/Icons/T-T-Icon-BR-CampingTentItem.T-T-Icon-BR-CampingTentItem"))
		for ((i, stashedItem) in campsiteData.stashed_items.withIndex()) {
			val item = stashedItem.asItemStack()
			if (item == null) {
				embed.addField("Slot %,d".format(i + 1), "<Empty>", false)
				continue
			}
			val rarityIcon = getEmoteByName(item.rarity.name.toLowerCase() + '2')?.asMention
			embed.addField("Slot %,d".format(i + 1), rarityIcon + ' ' + item.render(), false)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun FortCampsiteAccountItem.CachedItemData.asItemStack(): FortItemStack? {
		if (item_definition_persistent_name.isNullOrEmpty()) {
			return null
		}
		val item = FortItemStack(item_definition_persistent_name, item_count)
		item.attributes.addProperty("level", item_level)
		item.attributes.addProperty("loadedAmmo", item_loaded_ammo)
		return item
	}
}