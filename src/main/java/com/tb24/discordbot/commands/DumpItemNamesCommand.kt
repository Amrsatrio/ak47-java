package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.Rune
import com.tb24.discordbot.util.AttachmentUpload
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.format
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortAlterationItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortSchematicItemDefinition

class DumpItemNamesCommand : BrigadierCommand("dumpitemnames", "Gives you a JSON file of a map of template ID to their display names.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { AssetManager.INSTANCE.assetRegistry.templateIdToAssetDataMap.isNotEmpty() && Rune.isBotDev(it) }
		.executes {
			val source = it.source
			source.loading("Loading objects")
			val map = sortedMapOf<String, String>()
			for ((templateId, assetData) in AssetManager.INSTANCE.assetRegistry.templateIdToAssetDataMap) {
				val obj = loadObject(assetData.objectPath)
				val dn = when (obj) {
					is FortAlterationItemDefinition -> obj.Description
					is FortSchematicItemDefinition -> FortItemStack(templateId, 1).transformedDefData?.DisplayName
					is FortItemDefinition -> obj.DisplayName
					else -> continue
				}
				val text = dn?.format()
				if (!text.isNullOrEmpty()) {
					map[templateId] = text
				}
			}
			source.complete(AttachmentUpload(EpicApi.GSON.newBuilder().setPrettyPrinting().create().toJson(map).toByteArray(), "result.json"))
			Command.SINGLE_SUCCESS
		}
}