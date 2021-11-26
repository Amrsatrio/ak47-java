package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.util.getInt

class StwAccoladesCommand : BrigadierCommand("stwaccolades", "Shows the amount of Battle Royale XP earned from STW.", arrayOf("sbx")) {
    override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
            .withPublicProfile(::display, "Getting STW profile data")

    private fun display(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
        val source = c.source
        source.ensureCompletedCampaignTutorial(campaign)
        val xpItem = campaign.items.values.firstOrNull { it.templateId == "Token:stw_accolade_tracker" } ?: FortItemStack("Token:stw_accolade_tracker", 0)
        val current = xpItem.attributes.getInt("daily_xp")
        val max = xpItem.defData.get<Int>("MaxDailyXP")
        val embed = source.createEmbed(campaign.owner)
                .setTitle("STW Battle Royale XP")
                .setDescription("`%s`\n%,d / %,d".format(
                        Utils.progress(current, max, 32),
                        current, max))
        if (current == max)
            embed.setFooter("Daily limit reached")
        source.complete(null, embed.build())
        return Command.SINGLE_SUCCESS
    }
}