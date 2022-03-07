package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.stwBulk
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.util.format
import com.tb24.fn.util.getDateISO
import com.tb24.fn.util.getInt
import com.tb24.fn.util.getString
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.exports.FortAccoladeItemDefinition
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
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
		val result = get(campaign)
		val (current, max) = result
		val embed = source.createEmbed(campaign.owner)
			.setTitle("STW Battle Royale XP")
			.addField(if (current >= max) "✅ Daily limit reached" else "Daily limit progress", "`%s`\n%,d / %,d %s".format(
				Utils.progress(current, max, 32),
				current, max, xpEmote?.asMention), false)
		if (!result.outdated) {
			result.tracker.attributes.getAsJsonArray("match_accolades")?.forEach { matchAccolade_ ->
				val matchAccolade = matchAccolade_.asJsonObject
				val primaryMissionName = matchAccolade.getString("primaryMissionName")
				val missionInfo = AssetManager.INSTANCE.assetRegistry.lookup("FortMissionInfo", primaryMissionName)?.let { loadObject(it) }
				val missionName = missionInfo?.getOrNull<FText>("MissionName")?.format() ?: primaryMissionName
				val accoladesText = matchAccolade.getAsJsonArray("accoladesToGrant")?.joinToString("\n") { accoladeToGrant_ ->
					val accoladeToGrant = accoladeToGrant_.asJsonObject
					val (type, name) = accoladeToGrant.getString("templateToGrant")!!.split(':')
					val accoladeDef = AssetManager.INSTANCE.assetRegistry.lookup(type, name)?.let { loadObject<FortAccoladeItemDefinition>(it) }
					"%,d \u00d7 %s - %,d %s".format(accoladeToGrant.getInt("numToGrant"), accoladeDef?.DisplayName?.format() ?: name, accoladeToGrant.getInt("realXPGranted"), xpEmote?.asMention)
				}
				embed.addField("Last mission: %s - %,d %s".format(missionName, matchAccolade.getInt("totalXPEarnedInMatch"), xpEmote?.asMention), accoladesText, true)
			}
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun executeBulk(source: CommandSourceStack, usersLazy: Lazy<Collection<GameProfile>>? = null): Int {
		source.conditionalUseInternalSession()
		val entries = stwBulk(source, usersLazy) { campaign ->
			val completedTutorial = (campaign.items.values.firstOrNull { it.templateId == "Quest:homebaseonboarding" }?.attributes?.get("completion_hbonboarding_completezone")?.asInt ?: 0) > 0
			if (!completedTutorial) return@stwBulk null

			val (current, max) = get(campaign)
			campaign.owner.displayName to if (current >= max) "✅" else "%,d / %,d %s".format(current, max, xpEmote?.asMention)
		}
		if (entries.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("All users we're trying to display have not completed the STW tutorial.")).create()
		}
		val embed = EmbedBuilder().setColor(COLOR_INFO)
		val inline = entries.size >= 6
		for (entry in entries) {
			if (embed.fields.size == 25) {
				source.complete(null, embed.build())
				embed.clearFields()
			}
			embed.addField(entry.first, entry.second, inline)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun get(campaign: McpProfile): Result {
		val xpItem = campaign.items.values.firstOrNull { it.templateId == "Token:stw_accolade_tracker" } ?: FortItemStack("Token:stw_accolade_tracker", 0)
		return Result(xpItem)
	}

	class Result(val tracker: FortItemStack) {
		val outdated: Boolean

		init {
			val lastUpdate = tracker.attributes.getDateISO("last_update")
			val today = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
			val lastUpdateDay = lastUpdate.time / (24 * 60 * 60 * 1000)
			outdated = today != lastUpdateDay
		}

		val current = if (outdated) 0 else tracker.attributes.getInt("daily_xp")
		val max = 1056000 //tracker.defData.get<Int>("MaxDailyXP")

		operator fun component1() = current
		operator fun component2() = max
	}
}