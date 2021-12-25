package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.replyPaginated
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.format
import com.tb24.fn.util.getInt
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder

class AccoladesCommand : BrigadierCommand("accolades", "Shows your earned BR accolades, this includes medals and XP grants.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting BR data")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val entries = athena.items.values.filter { it.primaryAssetType == "Accolades" }.map { AccoladeEntry(it) }.sortedBy { it.displayName }
			if (entries.isEmpty()) {
				throw SimpleCommandExceptionType(LiteralMessage("You have earned no accolades yet. Play Battle Royale to earn some!")).create()
			}
			source.replyPaginated(entries, 10) { content, page, pageCount ->
				val entriesStart = page * 10 + 1
				val entriesEnd = entriesStart + content.size
				val embed = source.createEmbed()
					.setTitle("Accolades")
					.setDescription("Showing %,d to %,d of %,d entries".format(entriesStart, entriesEnd - 1, entries.size))
					.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				content.forEach { it.addTo(embed) }
				MessageBuilder(embed)
			}
			Command.SINGLE_SUCCESS
		}

	class AccoladeEntry(val accolade: FortItemStack) {
		var displayName = accolade.defData?.DisplayName.format()
		var description = accolade.defData?.Description.format()
		var shortDescription = accolade.defData?.ShortDescription.format()

		init {
			if (displayName.isNullOrEmpty()) {
				displayName = shortDescription
				shortDescription = null
			}
			if (!shortDescription.isNullOrEmpty() && shortDescription == displayName) {
				shortDescription = null
			}
			if (description.isNullOrEmpty() && !shortDescription.isNullOrEmpty()) {
				description = shortDescription
			}
		}

		fun addTo(embed: EmbedBuilder) {
			embed.addField("%,d \u00d7 %s".format(accolade.attributes.getInt("earned_count"), if (displayName.isNullOrEmpty()) accolade.primaryAssetName.toLowerCase().substringAfterLast("accoladeid_") else displayName), if (description.isNullOrEmpty()) "<No description>" else description, false)
		}
	}
}