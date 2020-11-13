package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.string
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.Utils
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.format
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.fn.util.toObjectPath
import me.fungames.jfortniteparse.fort.exports.FortMtxOfferData
import net.dv8tion.jda.api.EmbedBuilder

@Suppress("EXPERIMENTAL_API_USAGE")
class CosmeticCommand : BrigadierCommand("cosmetic", "Shows info of a cosmetic by their ID.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("id", string())
			.executes { c ->
				val start = System.currentTimeMillis()
				val item = FortItemStack("", getString(c, "id"), 1)
				val defData = item.defData ?: throw SimpleCommandExceptionType(LiteralMessage("Not found")).create()
				val embed = EmbedBuilder()
					.setTitle(defData.DisplayName.format())
					.setDescription(defData.Description.format())
					.addField("Rarity", defData.Rarity.name.format(), false)
					.setThumbnail(Utils.benBotExportAsset(item.getPreviewImagePath(true).toString()))
					.setImage(Utils.benBotExportAsset(defData.DisplayAssetPath?.load<FortMtxOfferData>()?.DetailsImage?.ResourceObject?.toObjectPath()))
					.setFooter("${System.currentTimeMillis() - start}ms")
					.setColor(0x40FAA1)
				defData.GameplayTags?.apply {
					embed.addField("Gameplay Tags", joinToString("\n"), false)
				}
				c.source.complete(null, embed.build())
				Command.SINGLE_SUCCESS
			}
		)
}