package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.L10N
import com.tb24.discordbot.Rune
import com.tb24.discordbot.commands.arguments.CatalogOfferArgument.Companion.catalogOffer
import com.tb24.discordbot.commands.arguments.CatalogOfferArgument.Companion.getCatalogEntry
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.getUsers
import com.tb24.discordbot.commands.arguments.UserArgument.Companion.users
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.gamesubcatalog.CatalogOffer
import com.tb24.fn.model.gamesubcatalog.EStoreCurrencyType
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.commoncore.GiftCatalogEntry
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import com.tb24.fn.util.format
import com.tb24.fn.util.getAffiliateNameRespectingSetDate
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Role

class GiftCommand : BrigadierCommand("gift", "Gifts a friend an offer from the item shop.", arrayOf("g")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::hasPremium)
		.then(argument("item number", catalogOffer())
			.then(argument("recipients", users(1))
				.executes { execute(it.source, getCatalogEntry(it, "item number"), getUsers(it, "recipients").values.first()) }
			)
		)

	private fun execute(source: CommandSourceStack, catalogOffer: CatalogOffer, recipient: GameProfile): Int {
		val ce = catalogOffer.holder()
		if (catalogOffer.giftInfo == null || !catalogOffer.giftInfo.bIsEnabled) {
			throw SimpleCommandExceptionType(LiteralMessage("${ce.friendlyName} is not giftable.")).create()
		}
		if (recipient.id == source.api.currentLoggedIn.id) {
			throw SimpleCommandExceptionType(LiteralMessage("Please use `${source.prefix}buy ${catalogOffer.__ak47_index + 1}` instead of gifting ${ce.friendlyName} to yourself.")).create()
		}
		source.loading("Preparing your gift")
		val profileManager = source.api.profileManager
		profileManager.dispatchClientCommandRequest(QueryProfile()).await()
		val commonCore = profileManager.getProfileData("common_core")
		if (!(commonCore.stats as CommonCoreProfileStats).mfa_enabled) {
			source.complete(null, source.createEmbed().setColor(COLOR_WARNING)
				.setTitle("⚠ Hold up before gifting!")
				.setDescription("You haven't claimed the two-factor authentication reward, which needs to be claimed first before sending gifts. Please enable two-factor authentication and claim its rewards by doing `${source.prefix}claim2fa` or by logging in to the game.\n\n[Enable two-factor authentication on epicgames.com](${source.generateUrl("https://www.epicgames.com/account/password#2fa-signup")})")
				.build())
			return 0
		}
		val eligibilityResponse = try {
			source.loading("Checking eligibility")
			source.api.fortniteService.checkGiftEligibility(recipient.id, catalogOffer.offerId).exec().body()!!
		} catch (e: HttpException) {
			when (e.epicError.errorCode) {
				"errors.com.epicgames.modules.gamesubcatalog.purchase_not_allowed" -> L10N.AlreadyOwned to L10N.AlreadyOwnedText
				"errors.com.epicgames.modules.gamesubcatalog.receiver_will_not_accept_gifts" -> L10N.GiftDeclined to L10N.OwnedText
				"errors.com.epicgames.modules.profile.profile_not_found" -> L10N.NotFortnitePlayer to L10N.NotPlayerText
				else -> null
			}?.run { source.completeWarning(first.format(), second.format()); return 0 }
			throw e
		}
		val settings = getGiftSettings(source)
		val giftMessage = settings.message.ifEmpty { L10N.MESSAGE_BOX_DEFAULT_MSG.format() }
		val price = eligibilityResponse.price
		val displayData = OfferDisplayData(catalogOffer)
		val embed = source.createEmbed()
			.setTitle("Confirm your Gift")
			.setDescription(L10N.CANT_BE_REFUNDED.format())
			.addField(L10N.format("catalog.items"), ce.compiledNames.mapIndexed { i, s ->
				val correspondingItemGrant = catalogOffer.itemGrants[i]
				val strike = if (eligibilityResponse.items.none { it.templateId == correspondingItemGrant.templateId }) "~~" else ""
				strike + s + strike
			}.joinToString("\n"), false)
			.addField("Recipient", recipient.displayName?.run { escapeMarkdown() + " - " } + "`${recipient.id}`", false)
			.addField("Gift message", giftMessage, false)
			.addField(L10N.format("catalog.total_price"), price.render(), true)
			.addField(L10N.format("catalog.balance"), price.getAccountBalanceText(profileManager), true)
			.setThumbnail(Utils.benBotExportAsset(displayData.imagePath))
			.setColor(displayData.presentationParams?.vector?.get("Background_Color_B") ?: Role.DEFAULT_COLOR_RAW)
		if (price.currencyType == EStoreCurrencyType.MtxCurrency) {
			embed.addField(L10N.format("catalog.mtx_platform"), (commonCore.stats as CommonCoreProfileStats).current_mtx_platform.name, true)
				.addField(L10N.format("sac.verb"), getAffiliateNameRespectingSetDate(commonCore) ?: L10N.format("common.none"), false)
		}
		if (!source.complete(null, embed.build()).yesNoReactions(source.author).await()) {
			throw SimpleCommandExceptionType(LiteralMessage("Gift canceled.")).create()
		}
		source.errorTitle = "Gift Failed"
		source.loading("Sending gifts")
		try {
			profileManager.dispatchClientCommandRequest(GiftCatalogEntry().apply {
				offerId = catalogOffer.offerId
				currency = price.currencyType
				currencySubType = price.currencySubType
				expectedTotalPrice = price.basePrice
				gameContext = "Frontend.ItemShopScreen"
				receiverAccountIds = arrayOf(recipient.id)
				giftWrapTemplateId = "GiftBox:gb_default"
				personalMessage = giftMessage
			}).await()
			source.complete("✅ All gifts were delivered successfully. Now you have ${price.getAccountBalanceText(profileManager)} left.")
			return Command.SINGLE_SUCCESS
		} catch (e: HttpException) {
			val epicError = e.epicError
			if (epicError.errorCode == "errors.com.epicgames.modules.gamesubcatalog.gift_recipient_not_eligible") {
				source.completeWarning(source.errorTitle, getErrorMsg(epicError.messageVars[0]).format(recipient.displayName?.escapeMarkdown() ?: "`${recipient.id}`"))
				return 0
			}
			throw e
		}
	}

	private fun getErrorMsg(errorCode: String): FText = when (errorCode) {
//		"errors.com.epicgames.fortnite.gift_recipient_has_reached_limit" -> L10N.GiftFailedReceiveLimitReached
		"errors.com.epicgames.modules.gamesubcatalog.gift_recipient_is_not_eligible_friend" -> L10N.GiftFailedFriendRequirement
		"errors.com.epicgames.fortnite.gift_recipient_on_different_platform" -> L10N.GiftFailedCrossPlatform
		"errors.com.epicgames.modules.gamesubcatalog.purchase_not_allowed" -> L10N.GiftFailedAlreadyOwned
		"errors.com.epicgames.modules.gamesubcatalog.receiver_will_not_accept_gifts" -> L10N.GiftFailedOptOut
		else -> L10N.GiftFailedGeneric
	}

	private inline fun CommandSourceStack.completeWarning(title: String?, body: String? = null, footer: String? = null) =
		complete(null, EmbedBuilder().setColor(COLOR_WARNING).setTitle("⚠ $title").setDescription(body).setFooter(body).build())
}