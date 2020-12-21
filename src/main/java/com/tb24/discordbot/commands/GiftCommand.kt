package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.L10N
import com.tb24.discordbot.commands.arguments.CatalogOfferArgument.Companion.catalogEntry
import com.tb24.discordbot.commands.arguments.CatalogOfferArgument.Companion.getCatalogEntry
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.getUsers
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.users
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.EStoreCurrencyType
import com.tb24.fn.model.mcpprofile.attributes.CommonCoreProfileAttributes
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commoncore.GiftCatalogEntry
import com.tb24.fn.util.CatalogHelper
import com.tb24.fn.util.format
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import net.dv8tion.jda.api.EmbedBuilder
import kotlin.math.min

class GiftCommand : BrigadierCommand("gift", "Gifts up to 4 friends a shop entry from current Battle Royale item shop.", arrayOf("g")) {
	val FAILED_FORMATS = arrayOf(FText("{0}"), L10N.GiftFailedTwoAccounts, L10N.GiftFailedThreeAccounts, L10N.GiftFailedFourAccounts, L10N.GiftFailedFivePlusAccounts)

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("item number", catalogEntry())
			.then(argument("recipients", users(4))
				.executes { execute(it.source, getCatalogEntry(it, "item number"), getUsers(it, "recipients")) }
			)
		)

	private fun execute(source: CommandSourceStack, catalogOffer: CatalogOffer, recipients: Map<String, GameProfile>): Int {
		val ce = catalogOffer.holder()
		if (catalogOffer.giftInfo == null || !catalogOffer.giftInfo.bIsEnabled) {
			throw SimpleCommandExceptionType(LiteralMessage("${ce.friendlyName} is not giftable.")).create()
		}
		source.loading("Preparing your gift")
		val profileManager = source.api.profileManager
		profileManager.dispatchClientCommandRequest(QueryProfile()).await()
		val commonCore = profileManager.getProfileData("common_core")
		if (!(commonCore.stats.attributes as CommonCoreProfileAttributes).mfa_enabled) {
			source.complete(null, source.createEmbed()
				.setTitle("⚠ Hold up before gifting!")
				.setDescription("You haven't claimed the two-factor authentication reward, which needs to be claimed first before sending gifts. Please enable two-factor authentication and claim its rewards by doing `+claim2fa` or by logging in to the game.\n\n[Enable two-factor authentication on epicgames.com](${source.generateUrl("https://www.epicgames.com/account/password#2fa-signup")})")
				.setColor(0xFFF300)
				.build())
			return 0
		}
		if (recipients.size == 1) {
			try {
				source.loading("Checking eligibility")
				source.api.fortniteService.checkGiftEligibility(recipients.values.first().id, catalogOffer.offerId).exec()
			} catch (e: HttpException) {
				var errorTitle: FText? = null
				var errorText: FText? = null
				when (e.epicError.errorCode) {
					"errors.com.epicgames.modules.gamesubcatalog.purchase_not_allowed" -> {
						errorTitle = L10N.AlreadyOwned
						errorText = L10N.AlreadyOwnedText
					}
					"errors.com.epicgames.modules.gamesubcatalog.receiver_will_not_accept_gifts" -> {
						errorTitle = L10N.GiftDeclined
						errorText = L10N.OwnedText
					}
					"errors.com.epicgames.modules.profile.profile_not_found" -> {
						errorTitle = L10N.NotFortnitePlayer
						errorText = L10N.NotPlayerText
					}
				}
				if (errorTitle != null) {
					source.complete(null, EmbedBuilder()
						.setTitle("⚠ ${errorText.format()}")
						.setDescription(errorText.format())
						.setColor(0xFFF300)
						.build())
					return 0
				}
				throw e
			}
		}
		val giftMessage = L10N.MESSAGE_BOX_DEFAULT_MSG.format()
		ce.resolve(profileManager)
		val price = ce.price
		val embed = source.createEmbed()
			.setColor(0x00FF00)
			.setTitle("Confirm your Gift")
			.setDescription(L10N.CANT_BE_REFUNDED.format())
			.addField(L10N.format("catalog.items"), ce.compiledNames.joinToString("\n"), false)
			.addField("Recipients", recipients.values.mapIndexed { i, v -> "${i + 1}. ${v.displayName} - ${v.id}" }.joinToString("\n"), false)
			// .addField("Gift message", giftMessage, false)
			.addField(L10N.format("catalog.total_price"), price.render(recipients.size), true)
			.addField(L10N.format("catalog.balance"), price.getAccountBalanceText(profileManager), true)
		if (price.currencyType == EStoreCurrencyType.MtxCurrency) {
			embed.addField(L10N.format("catalog.mtx_platform"), (commonCore.stats.attributes as CommonCoreProfileAttributes).current_mtx_platform.name, true)
				.addField(L10N.format("sac.verb"), CatalogHelper.getAffiliateNameRespectingSetDate(commonCore) ?: L10N.format("common.none"), false)
		}
		if (source.complete(null, embed.build()).yesNoReactions(source.author).await()) {
			source.errorTitle = "Gift Failed"
			source.loading("Sending gifts")
			try {
				profileManager.dispatchClientCommandRequest(GiftCatalogEntry().apply {
					offerId = catalogOffer.offerId
					currency = price.currencyType
					currencySubType = price.currencySubType
					expectedTotalPrice = recipients.size * price.basePrice
					gameContext = "Frontend.ItemShopScreen"
					receiverAccountIds = recipients.keys.toTypedArray()
					giftWrapTemplateId = "GiftBox:gb_default"
					personalMessage = giftMessage
				}).await()
				source.complete("✅ All gifts were delivered successfully. Now you have ${price.getAccountBalanceText(profileManager)} left.")
				return Command.SINGLE_SUCCESS
			} catch (e: HttpException) {
				val epicError = e.epicError
				if (epicError.errorCode == "errors.com.epicgames.modules.gamesubcatalog.gift_recipient_not_eligible") {
					val failedAccIds = epicError.messageVars[0].substring(1, epicError.messageVars[0].length - 1).split(", ")
					val failedReasons = epicError.messageVars[1].substring(1, epicError.messageVars[1].length - 1).split(", ")
					val fmt = arrayOfNulls<String>(4)
					for (i in failedAccIds.indices) {
						val errorMsg = getErrorMsg(failedReasons[i])
						if (i < 4) {
							val gameProfile = recipients[failedAccIds[i]]
							fmt[i] = errorMsg.format(gameProfile?.displayName ?: failedAccIds[i]).toString() + if (errorMsg == L10N.GiftFailedGeneric) " (${failedReasons[i]})" else ""
						}
					}
					source.complete(null, EmbedBuilder()
						.setTitle("⚠ ${source.errorTitle}")
						.setDescription(FAILED_FORMATS[min(failedAccIds.size, 5) - 1].format(fmt))
						.setColor(0xFFF300)
						.build())
					return 0
				}
				throw e
			}
		} else {
			throw SimpleCommandExceptionType(LiteralMessage("Gift canceled.")).create()
		}
	}

	private fun getErrorMsg(errorCode: String): FText = when (errorCode) {
//		"errors.com.epicgames.fortnite.gift_recipient_has_reached_limit" -> L10N.GiftFailedReceiveLimitReached
		"errors.com.epicgames.modules.gamesubcatalog.gift_recipient_is_not_eligible_friend\n" -> L10N.GiftFailedFriendRequirement
		"errors.com.epicgames.fortnite.gift_recipient_on_different_platform" -> L10N.GiftFailedCrossPlatform
		"errors.com.epicgames.modules.gamesubcatalog.purchase_not_allowed" -> L10N.GiftFailedAlreadyOwned
		"errors.com.epicgames.modules.gamesubcatalog.receiver_will_not_accept_gifts" -> L10N.GiftFailedOptOut
		else -> L10N.GiftFailedGeneric
	}
}