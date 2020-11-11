package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.ChannelsManager
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.AccountMutationPayload
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.Utils
import net.dv8tion.jda.api.EmbedBuilder
import java.awt.Color
import java.util.*
import kotlin.math.abs

class AccountCommand : BrigadierCommand("account", "Account commands.", listOf("a")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes(::summary)
		.then(literal<CommandSourceStack>("displayname")
			.then(argument<CommandSourceStack, String>("new name", greedyString())
				.executes { setDisplayName(it, StringArgumentType.getString(it, "new name")) }
			)
		)

	private fun summary(c: CommandContext<CommandSourceStack>): Int {
		val source = c.source
		source.ensureSession()
		if (!source.complete(null, source.createEmbed()
				.setTitle("✋ Hold up!")
				.setDescription("You're about to view the account details of ${source.api.currentLoggedIn.displayName}. Some of the data that we will send here might be sensitive, such as real name or Facebook name. We don't recommend to proceed if this account isn't yours.\n\nAre you sure you want to continue? (❌ in 30s)")
				.setColor(0xFFF300)
				.build()).yesNoReactions(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
		source.loading("Getting account data")
		val avatarKeys = source.session.channelsManager.getUserSettings(source.api.currentLoggedIn.id, "avatar", "avatarBackground")
		val data = source.api.accountService.getById(source.api.currentLoggedIn.id).exec().body()!!
		source.complete(null, EmbedBuilder()
			.setTitle("Epic Account Summary")
			.addField("Account ID", "||${data.id}||", false)
			.addField("Name", "||${data.name} ${data.lastName}||", true)
			.addField("Email", "||${data.email}||${if (data.emailVerified) " ✅" else " (unverified)"}", true)
			.addField("Display Name", StringBuilder("Current: ${data.epicDisplayName.orDash()}")
				.append("\nChanges: ${Formatters.num.format(data.numberOfDisplayNameChanges)}")
				.append("\nLast changed: ${data.lastDisplayNameChange?.run { renderWithRelative() } ?: "\u2014"}")
				.apply {
					if (data.canUpdateDisplayName != true) {
						append("\nNext change: ${data.canUpdateDisplayNameNext?.run { renderWithRelative() } ?: "\u2014"}")
					}
				}.toString(), false)
			.addField("Linked Accounts", source.api.accountService.getExternalAuths(source.api.currentLoggedIn.id).exec().body()!!.takeIf { it.isNotEmpty() }?.joinToString("\n\n") {
				"__${it.type}: ||**${it.externalDisplayName.orDash()}**||__\nAdded: ${it.dateAdded?.run { renderWithRelative() } ?: "\u2014"}"
			} ?: "No linked accounts", false)
			.setThumbnail("https://cdn2.unrealengine.com/Kairos/portraits/${avatarKeys[0]}.png?preview=1")
			.setColor(Color.decode(Utils.DEFAULT_GSON.fromJson(avatarKeys[1], Array<String>::class.java)[ChannelsManager.ColorIndex.DARK.ordinal]))
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun setDisplayName(c: CommandContext<CommandSourceStack>, newName: String): Int {
		val source = c.source
		source.ensureSession()
		source.loading("Checking `$newName` for validity")
		runCatching { source.api.accountService.getByDisplayName(newName).exec() }.getOrNull()?.body()?.apply {
			throw SimpleCommandExceptionType(LiteralMessage("The Epic display name `$displayName` has already been taken. Please choose another name.")).create()
		}
		val oldName = source.api.currentLoggedIn.epicDisplayName.orDash()
		if (!source.complete(null, source.createEmbed()
				.setTitle("Change display name?")
				.setDescription("You're about to change the display name of account `${source.api.currentLoggedIn.id}`:\n\n`${oldName.orDash()}` \u2192 `$newName`\n\nThis action will be recorded in the Account History as `HISTORY_ACCOUNT_UPDATE`. Are you sure you want to continue? (❌ in 30s)")
				.setColor(0xFFF300)
				.build()).yesNoReactions(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
		source.loading("Changing display name")
		val response = source.api.accountService.editAccountDetails(source.api.currentLoggedIn.id, AccountMutationPayload().apply {
			displayName = newName
		}).exec().body()!!
		source.api.currentLoggedIn = response.accountInfo.run { GameProfile(id, epicDisplayName) }
		if (response.oauthSession != null) {
			source.api.userToken = response.oauthSession
			source.session.save()
		}
		source.complete(null, source.createEmbed()
			.setTitle("✅ Updated the Epic display name")
			.addField("Old name", oldName.orDash(), true)
			.addField("New name", response.accountInfo.epicDisplayName.orDash(), true)
			.setColor(0x40FAA1)
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun Date.renderWithRelative() = "${format()} (${relativeFromNow()})"

	private fun Date.relativeFromNow(): String {
		val delta = System.currentTimeMillis() - time
		val elapsedStr = StringUtil.formatElapsedTime(abs(delta), false).toString()
		return when {
			delta < 0L -> "in $elapsedStr"
			delta < 60L -> "just now"
			else /*delta > 0L*/ -> "$elapsedStr ago"
		}
	}

	private inline fun Any?.orDash() = this?.toString() ?: "\u2014"
}