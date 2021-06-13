package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.assetdata.FortFriendChestItemDefinition
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.FortFriendChestItem
import net.dv8tion.jda.api.EmbedBuilder

class FriendChestCommand : BrigadierCommand("friendchest", "Check your weekly alien artifacts progress.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting BR data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val items = athena.items.values.filter { it.primaryAssetType == "FriendChest" }
			val friendChestItem = items.first { it.primaryAssetName == "friendchest_s17_alienstylepoint" } // TODO figure out other friend chests
			val attrs = friendChestItem.getAttributes(FortFriendChestItem::class.java)
			val itemDef = friendChestItem.defData as? FortFriendChestItemDefinition
				?: throw SimpleCommandExceptionType(LiteralMessage("Friend chest item definition failed to load.")).create()
			source.complete(null, source.createEmbed()
				.setTitle("Friend Chest: Alien Artifacts")
				.addProgressField("This period", attrs.granted_this_period, itemDef.GrantsPerPeriod)
				.addProgressField("This season", attrs.granted_this_season, itemDef.GrantsPerSeason)
				.setFooter("Period resets")
				.setTimestamp(attrs.period_reset_time.toInstant())
				.build())
			Command.SINGLE_SUCCESS
		}

	private inline fun EmbedBuilder.addProgressField(title: String, progress: Int, max: Int) =
		addField((if (progress >= max) "✅ " else "") + title, "`%s`\n%,d / %,d".format(Utils.progress(progress, max, 32), progress, max), true)
}