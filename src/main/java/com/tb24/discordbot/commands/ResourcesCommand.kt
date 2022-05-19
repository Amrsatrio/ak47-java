package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.L10N
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.getItemIconEmoji
import com.tb24.discordbot.util.stwBulk
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.stats.CampaignProfileStats
import com.tb24.fn.util.Formatters
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.OptionType

class ResourcesCommand : BrigadierCommand("resources", "Displays a given user's STW resource items.", arrayOf("r")) {
	companion object {
		private val CATEGORIES = mapOf(
			"perk" to listOf(
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
			"evo" to listOf(
				"AccountResource:reagent_c_t01",
				"AccountResource:reagent_c_t02",
				"AccountResource:reagent_c_t03",
				"AccountResource:reagent_c_t04",
				"AccountResource:reagent_people",
				"AccountResource:reagent_traps",
				"AccountResource:reagent_weapons"
			),
			"xp" to listOf(
				"AccountResource:heroxp",
				"AccountResource:schematicxp",
				"AccountResource:personnelxp",
				"AccountResource:phoenixxp"
			),
			"supercharger" to listOf(
				"AccountResource:reagent_promotion_heroes",
				"AccountResource:reagent_promotion_survivors",
				"AccountResource:reagent_promotion_traps",
				"AccountResource:reagent_promotion_weapons"
			),
			"llama" to listOf(
				"AccountResource:voucher_basicpack",
				"AccountResource:voucher_cardpack_bronze",
				"AccountResource:voucher_cardpack_jackpot",
			),
			"flux" to listOf(
				"AccountResource:reagent_evolverarity_sr",
				"AccountResource:reagent_evolverarity_vr",
				"AccountResource:reagent_evolverarity_r"
			),
			"voucher" to listOf(
				"AccountResource:voucher_herobuyback",
				"AccountResource:voucher_item_buyback"
			),
			"xpboost" to listOf(
				"ConsumableAccountItem:smallxpboost",
				"ConsumableAccountItem:smallxpboost_gift"
			),
			"currency" to listOf(
				"AccountResource:campaign_event_currency",
				"AccountResource:currency_xrayllama",
				"AccountResource:eventcurrency_scaling"
			)
		)
		private val SINGLE_LINE_TYPES = hashSetOf("supercharger", "llamas", "flux", "voucher", "xpboost")
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.withPublicProfile(::execute, "Getting resources data")
		.then(literal("bulk")
			.then(argument("type", StringArgumentType.word())
				.executes { executeBulk(it.source, StringArgumentType.getString(it, "type")) }
				.then(argument("users", UserArgument.users(100))
					.executes { executeBulk(it.source, StringArgumentType.getString(it, "type"), lazy { UserArgument.getUsers(it, "users").values }) }
				)
			)
		)

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("view", description)
			.withPublicProfile(::execute, "Getting resources data")
		)
		.then(subcommand("bulk", "Shows the amounts of a type of resources of multiple users.")
			.option(OptionType.STRING, "type", "The type of resource to show", true, CATEGORIES.keys.map { Choice(L10N.format("resource.category.$it.name"), it) }.toTypedArray())
			.option(OptionType.STRING, "users", "Users to display or leave blank to display your saved accounts", argument = UserArgument.users(100))
			.executes { source ->
				val usersResult = source.getArgument<UserArgument.Result>("users")
				executeBulk(source, source.getOption("type")!!.asString, usersResult?.let { lazy { it.getUsers(source).values } })
			}
		)

	private fun execute(source: CommandSourceStack, campaign: McpProfile): Int {
		source.ensureCompletedCampaignTutorial(campaign)
		val embed = source.createEmbed(campaign.owner).setFooter("Design by a.bakedpotato")
		for (categoryName in CATEGORIES.keys) {
			embed.addField(L10N.format("resource.category.$categoryName.name"), get(campaign, categoryName), true)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun executeBulk(source: CommandSourceStack, type: String, usersLazy: Lazy<Collection<GameProfile>>? = null): Int {
		val type = type.toLowerCase()
		if (type !in CATEGORIES) {
			throw SimpleCommandExceptionType(LiteralMessage("Unknown resource type $type. Valid values are: (case insensitive)```\n${CATEGORIES.keys.joinToString()}\n```")).create()
		}
		source.conditionalUseInternalSession()
		val entries = stwBulk(source, usersLazy) { campaign ->
			val completedTutorial = (campaign.items.values.firstOrNull { it.templateId == "Quest:homebaseonboarding" }?.attributes?.get("completion_hbonboarding_completezone")?.asInt ?: 0) > 0
			if (!completedTutorial) return@stwBulk null

			campaign.owner.displayName to get(campaign, type, type in SINGLE_LINE_TYPES)
		}
		if (entries.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("All users we're trying to display have not completed the STW tutorial.")).create()
		}
		val embed = EmbedBuilder().setColor(COLOR_INFO)
		for (entry in entries) {
			if (embed.fields.size == 24) {
				source.complete(null, embed.build())
				embed.clearFields()
			}
			embed.addField(entry.first, entry.second, true)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun get(campaign: McpProfile, categoryName: String, singleLine: Boolean = false) =
		CATEGORIES[categoryName]!!.joinToString(if (singleLine) " " else "\n") { inTid ->
			val tid = if (inTid == "AccountResource:campaign_event_currency") (campaign.stats as CampaignProfileStats).event_currency.templateId ?: inTid else inTid
			val item = campaign.items.values.firstOrNull { it.templateId == tid } ?: FortItemStack(tid, 0)
			(getItemIconEmoji(item)?.asMention ?: tid) + ' ' + Formatters.num.format(item.quantity)
		}
}