package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.relativeFromNow
import com.tb24.fn.model.assetdata.FortFriendChestItemDefinition
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.item.FortFriendChestItem
import net.dv8tion.jda.api.EmbedBuilder

class FriendChestCommand : BrigadierCommand("friendchest", "Check your weekly rainbow ink progress.", arrayOf("fc")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting BR data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val items = athena.items.values.filter { it.primaryAssetType == "FriendChest" }
			val friendChestItem = items.firstOrNull { it.primaryAssetName == "friendchest_s18_inkstylepoint" } // TODO figure out other friend chests
				?: throw SimpleCommandExceptionType(LiteralMessage("You haven't earned a Rainbow Ink from Cosmic Chests.")).create()
			val attrs = friendChestItem.getAttributes(FortFriendChestItem::class.java)
			val itemDef = friendChestItem.defData as? FortFriendChestItemDefinition
				?: throw SimpleCommandExceptionType(LiteralMessage("Friend chest item definition failed to load.")).create()
			source.complete(null, source.createEmbed()
				.setTitle("Friend Chest: Rainbow Ink")
				.addProgressField("This period", attrs.granted_this_period, itemDef.GrantsPerPeriod)
				.addProgressField("This season", attrs.granted_this_season, itemDef.GrantsPerSeason)
				.setFooter("Period resets " + attrs.period_reset_time.relativeFromNow())
				.setTimestamp(attrs.period_reset_time.toInstant())
				.build())
			// TODO show previous season friend chest progress
			Command.SINGLE_SUCCESS
		}

	private inline fun EmbedBuilder.addProgressField(title: String, progress: Int, max: Int) =
		addField(title, "`%s`\n%,d / %,d%s".format(Utils.progress(progress, max, 32), progress, max, (if (progress >= max) " ✅" else "")), true)
}