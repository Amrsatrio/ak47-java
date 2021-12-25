package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.item.ItemTypeResolver
import com.tb24.discordbot.util.getEmoteByName
import com.tb24.discordbot.util.replyPaginated
import com.tb24.discordbot.util.textureEmote
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import net.dv8tion.jda.api.MessageBuilder

class WorkersCommand : BrigadierCommand("survivors", "Shows your or a given player's survivors.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting survivors")

	private fun execute(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
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
			val nothing = getEmoteByName("nothing")?.asMention ?: ""
			embed.setDescription(content.joinToString("\n") { item ->
				renderWorker(item, nothing)
			})
			MessageBuilder(embed)
		}

		return Command.SINGLE_SUCCESS
	}

	private fun renderWorker(item: FortItemStack, nothing: String = getEmoteByName("nothing")?.asMention ?: ""): String {
		val itemTypeResolver = ItemTypeResolver.resolveItemType(item)
		val dn = item.displayName
		return "%s%s%s%s %,d%s".format(
			getEmoteByName(item.rarity.name.toLowerCase() + '2')?.asMention ?: nothing,
			textureEmote(itemTypeResolver.leftImg)?.asMention ?: nothing,
			textureEmote(itemTypeResolver.middleImg)?.asMention ?: nothing,
			textureEmote(itemTypeResolver.rightImg)?.asMention ?: nothing,
			item.attributes["level"]?.asInt ?: 0,
			if (dn.isNotEmpty()) " \u2014 $dn" else ""
		)
	}
}