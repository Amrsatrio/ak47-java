package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.getItemIconEmoji
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.fn.util.Formatters

class ResourcesCommand : BrigadierCommand("resources", "Displays a given user's STW resource items.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting resources data")

	private fun execute(source: CommandSourceStack, campaign: McpProfile): Int {
		source.ensureCompletedCampaignTutorial(campaign)
		val embed = source.createEmbed(campaign.owner).setFooter("Design by a.bakedpotato")
		for ((categoryName, categoryItemTypes) in mapOf(
			"Perk-UP" to listOf(
				"AccountResource:reagent_alteration_upgrade_sr",
				"AccountResource:reagent_alteration_upgrade_vr",
				"AccountResource:reagent_alteration_upgrade_r",
				"AccountResource:reagent_alteration_upgrade_uc",
				"AccountResource:reagent_alteration_generic",
				"AccountResource:reagent_alteration_gameplay_generic",
				"AccountResource:reagent_alteration_ele_fire",
				"AccountResource:reagent_alteration_ele_water",
				"AccountResource:reagent_alteration_ele_nature"
			),
			"Evo Mats" to listOf(
				"AccountResource:reagent_c_t01",
				"AccountResource:reagent_c_t02",
				"AccountResource:reagent_c_t03",
				"AccountResource:reagent_c_t04",
				"AccountResource:reagent_people",
				"AccountResource:reagent_traps",
				"AccountResource:reagent_weapons"
			),
			"XP" to listOf(
				"AccountResource:heroxp",
				"AccountResource:schematicxp",
				"AccountResource:personnelxp",
				"AccountResource:phoenixxp"
			),
			"Superchargers" to listOf(
				"AccountResource:reagent_promotion_heroes",
				"AccountResource:reagent_promotion_survivors",
				"AccountResource:reagent_promotion_traps",
				"AccountResource:reagent_promotion_weapons"
			),
			"Llamas" to listOf(
				"AccountResource:voucher_basicpack",
				"AccountResource:voucher_cardpack_bronze",
				"AccountResource:voucher_cardpack_jackpot",
			),
			"Flux" to listOf(
				"AccountResource:reagent_evolverarity_sr",
				"AccountResource:reagent_evolverarity_vr",
				"AccountResource:reagent_evolverarity_r"
			),
			"Vouchers" to listOf(
				"AccountResource:voucher_herobuyback",
				"AccountResource:voucher_item_buyback"
			),
			"XP Boosts" to listOf(
				"ConsumableAccountItem:smallxpboost",
				"ConsumableAccountItem:smallxpboost_gift"
			),
			"Miscellaneous" to listOf(
				(campaign.stats as CampaignProfileStats).event_currency.templateId,
				"AccountResource:currency_xrayllama",
				"AccountResource:eventcurrency_scaling"
			)
		)) {
			embed.addField(categoryName, categoryItemTypes.joinToString("\n") { tid ->
				val item = campaign.items.values.firstOrNull { it.templateId == tid } ?: FortItemStack(tid, 0)
				(getItemIconEmoji(item)?.asMention ?: tid) + ' ' + Formatters.num.format(item.quantity)
			}, true)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}