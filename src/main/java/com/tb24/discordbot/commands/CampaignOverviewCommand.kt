package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.getItemIconEmoji
import com.tb24.fn.model.mcpprofile.attributes.CampaignProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.Formatters

class CampaignOverviewCommand : BrigadierCommand("stw", "Displays summary of your Save the World data.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		/*.executes {
			val source = it.source
			source.complete(null, source.createEmbed()
				.setTitle("⚡ %.2f".format())
				.build())
			Command.SINGLE_SUCCESS
		}*/
		.executes(::execute)

	fun execute(c: CommandContext<CommandSourceStack>): Int {
		c.source.ensureSession()
		c.source.loading("Getting STW data")
		val profileManager = c.source.api.profileManager
		profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign").await()
		val campaign = profileManager.getProfileData("campaign")
		val embed = c.source.createEmbed().setColor(0x40FAA1)
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
				"AccountResource:voucher_cardpack_bronze"
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
				"AccountResource:currency_xrayllama",
				"AccountResource:eventcurrency_scaling",
				(campaign.stats.attributes as CampaignProfileAttributes).event_currency.templateId
			)
		)) {
			embed.addField(categoryName, categoryItemTypes.joinToString("\n") { tid ->
				(getItemIconEmoji(tid)?.asMention ?: tid) + ' ' + Formatters.num.format(campaign.items.values.firstOrNull { it.templateId == tid }?.quantity ?: 0)
			}, true)
		}
		c.source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}