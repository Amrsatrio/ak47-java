package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.BoolArgumentType.getBool
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.L10N
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commoncore.SetReceiveGiftsEnabled
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.util.format
import net.dv8tion.jda.api.EmbedBuilder

class GiftSettingsCommand : BrigadierCommand("giftsettings", "Manage your gift settings such as gift acceptance, wrap, and message.", arrayOf("giftconfig", "gs")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			source.loading("Getting gift settings")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
			val canReceiveGifts = (source.api.profileManager.getProfileData("common_core").stats as CommonCoreProfileStats).allowed_to_receive_gifts
			val settings = getGiftSettings(source)
			source.complete(null, source.createEmbed()
				.setTitle("Gift Settings")
				.addField("Can receive gifts", if (canReceiveGifts) "✅ Yes" else "❌ No", false)
				.addField("Wrap", settings.wrapText, false)
				.addField("Message (Applies to your Discord account)", settings.messageText, false)
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
			.then(literal("clear").executes { setGiftMessage(it.source, "") })
			.then(argument("gift message", greedyString())
				.executes {
					val message = getString(it, "gift message")
					if (message.length > 100) {
						throw SimpleCommandExceptionType(LiteralMessage("Gift message must not exceed 100 characters.")).create()
					}
					setGiftMessage(it.source, message)
				}
			)
		)
	/*.then(literal("wrap")
		.then(literal("clear").executes { setGiftWrap(it.source, "") })
		.then(argument("gift wrap", greedyString())
			.executes { setGiftWrap(it.source, getString(it, "gift wrap")) }
		)
	)*/

	private fun setGiftMessage(source: CommandSourceStack, message: String): Int {
		val settings = getGiftSettings(source)
		settings.message = message
		if (settings.persisted) {
			r.table("gift_settings").update(settings)
		} else {
			r.table("gift_settings").insert(settings)
		}.run(source.client.dbConn)
		source.complete(null, EmbedBuilder().setColor(COLOR_SUCCESS)
			.setTitle("✅ Updated gift message")
			.setDescription("**Disclaimer:** The message is tied to your Discord account, not to Epic accounts.")
			.addField("Message", settings.messageText, false)
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun setGiftWrap(source: CommandSourceStack, wrap: String): Int {
		val settings = getGiftSettings(source)
		settings.wrap = wrap
		if (settings.persisted) {
			r.table("gift_settings").update(settings)
		} else {
			r.table("gift_settings").insert(settings)
		}.run(source.client.dbConn)
		source.complete(null, EmbedBuilder().setColor(COLOR_SUCCESS)
			.setTitle("✅ Updated gift wrap")
			.addField("Wrap", settings.wrapText, false)
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun updateReceiveGifts(source: CommandSourceStack, receiveGifts: Boolean? = null): Int {
		source.ensureSession()
		source.loading(if (receiveGifts != null)
			"Changing your gift acceptance"
		else
			"Toggling your gift acceptance")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile()).await()
		val canReceiveGifts = (source.api.profileManager.getProfileData("common_core").stats as CommonCoreProfileStats).allowed_to_receive_gifts
		val newValue = receiveGifts ?: !canReceiveGifts
		if (newValue == canReceiveGifts) {
			throw SimpleCommandExceptionType(LiteralMessage(if (canReceiveGifts)
				"Your account is already configured to **accept** gifts."
			else
				"Your account is already configured to **reject** gifts.")).create()
		}
		source.api.profileManager.dispatchClientCommandRequest(SetReceiveGiftsEnabled().apply { bReceiveGifts = newValue }).await()
		source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
			.setDescription("✅ " + if (newValue)
				"Configured your account to **accept** gifts."
			else
				"Configured your account to **reject** gifts.")
			.build())
		return Command.SINGLE_SUCCESS
	}

	class GiftSettingsEntry {
		@JvmField var id: String
		@JvmField var wrap: String
		@JvmField var message: String
		@JvmField @Transient var persisted = false

		constructor() : this("", "", "") {
			persisted = true
		}

		constructor(id: String, wrap: String = "", message: String = "") {
			this.id = id
			this.wrap = wrap
			this.message = message
		}

		val wrapText get() = if (wrap.isNotEmpty()) wrap else "Default wrap: *Purple*"
		val messageText get() = if (message.isNotEmpty()) message else "Default message: *${L10N.MESSAGE_BOX_DEFAULT_MSG.format()}*"
	}
}

fun getGiftSettings(source: CommandSourceStack) = r.table("gift_settings")
	.get(source.author.id)
	.run(source.client.dbConn, GiftSettingsCommand.GiftSettingsEntry::class.java).first()
	?: GiftSettingsCommand.GiftSettingsEntry(source.author.id)