package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.BoolArgumentType.getBool
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.L10N
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.attributes.CommonCoreProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.SetReceiveGiftsEnabled
import com.tb24.fn.util.format

class GiftSettingsCommand : BrigadierCommand("giftsettings", "Manage your gift settings such as the gift wrap to use.", arrayListOf("gs")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Getting gift settings")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val canReceiveGifts = (source.api.profileManager.getProfileData("common_core").stats.attributes as CommonCoreProfileAttributes).allowed_to_receive_gifts
			val settings = /*r.table("giftsettings").get(source.author.id).run(source.client.dbConn, GiftSettingsEntry::class.java).first() ?: */GiftSettingsEntry(source.author.id)
			source.complete(null, source.createEmbed()
				.setTitle("Gift Settings")
				.addField("Can Receive Gifts", if (canReceiveGifts) "✔ Yes" else "❌ No", false)
				.addField("Wrap", settings.wrap ?: "Default Wrap: *Purple*", false)
				.addField("Message", settings.message ?: "Default Message: *${L10N.MESSAGE_BOX_DEFAULT_MSG.format()}*", false)
				.build())
			Command.SINGLE_SUCCESS
		}
		.then(literal("receive")
			.executes { updateReceiveGifts(it.source) }
			.then(argument("can receive gifts?", bool())
				.executes { updateReceiveGifts(it.source, getBool(it, "can receive gifts?")) }
			)
		)
		.then(literal("message")
			.then(argument("gift message", greedyString())
				.executes {
					r.table("giftsettings").insert(GiftSettingsEntry(it.source.author.id, null, null)).run(it.source.client.dbConn)
					Command.SINGLE_SUCCESS
				}
			)
		)

	private fun updateReceiveGifts(source: CommandSourceStack, receiveGifts: Boolean? = null): Int {
		source.loading(if (receiveGifts != null)
			"Changing your gift acceptance"
		else
			"Toggling your gift acceptance")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
		val canReceiveGifts = (source.api.profileManager.getProfileData("common_core").stats.attributes as CommonCoreProfileAttributes).allowed_to_receive_gifts
		val newValue = receiveGifts ?: !canReceiveGifts
		if (newValue == canReceiveGifts) {
			throw SimpleCommandExceptionType(LiteralMessage(if (canReceiveGifts)
				"Your account is already configured to **accept** gifts."
			else
				"Your account is already configured to **reject** gifts.")).create()
		}
		source.api.profileManager.dispatchClientCommandRequest(SetReceiveGiftsEnabled().apply { bReceiveGifts = newValue }).await()
		source.complete(null, source.createEmbed()
			.setDescription("✅ " + if (newValue)
				"Configured your account to **accept** gifts."
			else
				"Configured your account to **reject** gifts.")
			.setColor(0x4BDA74)
			.build())
		return Command.SINGLE_SUCCESS
	}

	class GiftSettingsEntry(
		val id: String,
		val wrap: String? = null,
		val message: String? = null
	)
}