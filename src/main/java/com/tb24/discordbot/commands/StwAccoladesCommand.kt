package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.Utils
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.util.getDateISO
import com.tb24.fn.util.getInt
import net.dv8tion.jda.api.EmbedBuilder

class StwAccoladesCommand : BrigadierCommand("stwaccolades", "Shows the amount of Battle Royale XP earned from STW.", arrayOf("sbx")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::display, "Getting STW profile data")
        .then(literal("bulk")
            .executes { executeBulk(it.source) }
            .then(argument("users", UserArgument.users(15))
                .executes { executeBulk(it.source, lazy { UserArgument.getUsers(it, "users").values }) }
            )
        )

	private fun display(c: CommandContext<CommandSourceStack>, campaign: McpProfile): Int {
		val source = c.source
		source.ensureCompletedCampaignTutorial(campaign)
        val (current, max) = get(campaign)
        val embed = source.createEmbed(campaign.owner)
			.setTitle("STW Battle Royale XP")
			.setDescription("`%s`\n%,d / %,d".format(
				Utils.progress(current, max, 32),
				current, max))
		if (current >= max)
			embed.setFooter("Daily limit reached")
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

    private fun executeBulk(source: CommandSourceStack, usersLazy: Lazy<Collection<GameProfile>>? = null): Int {
        source.conditionalUseInternalSession()
        val entries = stwBulk(source, usersLazy) { campaign ->
            val completedTutorial = (campaign.items.values.firstOrNull { it.templateId == "Quest:homebaseonboarding" }?.attributes?.get("completion_hbonboarding_completezone")?.asInt ?: 0) > 0
            if (!completedTutorial) return@stwBulk null

            val (current, max) = get(campaign)
            campaign.owner.displayName to if (current >= max) "✅" else "%,d / %,d".format(current, max)
        }
        if (entries.isEmpty()) {
            throw SimpleCommandExceptionType(LiteralMessage("All users we're trying to display have not completed the STW tutorial.")).create()
        }
        val embed = EmbedBuilder().setColor(COLOR_INFO)
		val inline = entries.size >= 6
        for (entry in entries) {
            embed.addField(entry.first, entry.second, inline)
        }
        source.complete(null, embed.build())
        return Command.SINGLE_SUCCESS
    }

    private fun get(campaign: McpProfile): Pair<Int, Int> {
        val xpItem = campaign.items.values.firstOrNull { it.templateId == "Token:stw_accolade_tracker" } ?: FortItemStack("Token:stw_accolade_tracker", 0)
        val lastUpdate = xpItem.attributes.getDateISO("last_update")
        val today = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        val lastUpdateDay = lastUpdate.time / (24 * 60 * 60 * 1000)
        val current = if (today == lastUpdateDay) xpItem.attributes.getInt("daily_xp") else 0
        val max = 1056000 //xpItem.defData.get<Int>("MaxDailyXP")
        return current to max
    }
}