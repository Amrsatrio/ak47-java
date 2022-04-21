package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.render
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
		val campsiteData = campsite?.getAttributes(FortCampsiteAccountItem::class.java)?.campsite_account_data
			?: throw SimpleCommandExceptionType(LiteralMessage("Tent data not found")).create()
		val embed = source.createEmbed().setThumbnail(Utils.benBotExportAsset("/CampsiteGameplay/Icons/T-T-Icon-BR-CampingTentItem.T-T-Icon-BR-CampingTentItem"))
		campsiteData.stashed_items?.forEachIndexed { i, stashedItem ->
			val item = stashedItem.asItemStack()
			if (item == null) {
				embed.addField("Slot %,d".format(i + 1), "<Empty>", false)
				return@forEachIndexed
			}
			embed.addField("Slot %,d".format(i + 1), item.render(showLevelAndTier = false), false)
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