package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.item.ItemComparator
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.render
import com.tb24.discordbot.util.replyPaginated
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import me.fungames.jfortniteparse.fort.exports.FortBuildingItemDefinition
import net.dv8tion.jda.api.MessageBuilder

class BackpackCommand : BrigadierCommand("backpack", "Shows your STW backpack inventory.", arrayOf("inventory", "inv", "theater0")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source, "theater0", "Backpack") }

	override fun getSlashCommand() = newCommandBuilder().executes { execute(it, "theater0", "Backpack") }
}

class OutpostCommand : BrigadierCommand("storage", "Shows your STW storage inventory.", arrayOf("outpost")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source, "outpost0", "Storage") }

	override fun getSlashCommand() = newCommandBuilder().executes { execute(it, "outpost", "Storage") }
}

private fun execute(source: CommandSourceStack, profileId: String, name: String): Int {
	source.ensureSession()
	source.loading("Loading storage data")
	source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), profileId).await()
	val outpost = source.api.profileManager.getProfileData(profileId)
	val entries = outpost.items.values.map { FortItemStack(it) }
		.filter { it.defData !is FortBuildingItemDefinition }
		.sortedWith(ItemComparator())
	source.replyPaginated(entries, 30) { content, page, pageCount ->
		val embed = source.createEmbed()
			.setTitle(name)
			.setFooter("Page %d/%d".format(page + 1, pageCount))
		embed.setDescription(content.joinToString { it.render() })
		MessageBuilder(embed)
	}
	return Command.SINGLE_SUCCESS
}