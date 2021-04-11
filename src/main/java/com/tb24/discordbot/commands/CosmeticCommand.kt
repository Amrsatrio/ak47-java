package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.Utils
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.format
import com.tb24.fn.util.getPreviewImagePath
import me.fungames.jfortniteparse.fort.exports.FortMtxOfferData
import net.dv8tion.jda.api.EmbedBuilder

class CosmeticCommand : BrigadierCommand("cosmetic", "Shows info of a cosmetic by their ID.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("id", greedyString())
			.executes { c ->
				val start = System.currentTimeMillis()
				val id = getString(c, "id")
				if (id.split(':').size < 2) {
					throw SimpleCommandExceptionType(LiteralMessage("Please retype it in this format: `PrimaryAssetType:primary_asset_name`")).create()
				}
				val item = FortItemStack(id, 1)
				val defData = item.defData ?: throw SimpleCommandExceptionType(LiteralMessage("Not found")).create()
				val embed = EmbedBuilder()
					.setTitle(item.displayName)
					.setDescription(defData.Description.format())
					.addField("Rarity", defData.Rarity.name.format(), false)
					.setThumbnail(Utils.benBotExportAsset(item.getPreviewImagePath(true)?.toString()))
					.setImage(Utils.benBotExportAsset(defData.DisplayAssetPath?.load<FortMtxOfferData>()?.DetailsImage?.ResourceObject?.value?.getPathName()))
					.setFooter("${System.currentTimeMillis() - start}ms")
				defData.GameplayTags?.apply {
					embed.addField("Gameplay Tags", joinToString("\n"), false)
				}
				c.source.complete(null, embed.build())
				Command.SINGLE_SUCCESS
			}
		)
}