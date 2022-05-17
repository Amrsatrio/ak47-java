package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.ItemArgument
import com.tb24.discordbot.util.*
import com.tb24.fn.EpicApi
import com.tb24.fn.model.EAthenaCustomizationCategory
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.McpVariantReader
import com.tb24.fn.model.assetdata.CustomDynamicColorSwatch
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.MarkItemSeen
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.subgame.SetCosmeticLockerSlot
import com.tb24.fn.model.mcpprofile.commands.subgame.SetItemFavoriteStatus
import com.tb24.fn.model.mcpprofile.item.FortCosmeticLockerItem
import com.tb24.fn.util.format
import com.tb24.fn.util.getPreviewImagePath
import me.fungames.jfortniteparse.fort.exports.*
import me.fungames.jfortniteparse.fort.exports.variants.FortCosmeticItemTexture
import me.fungames.jfortniteparse.fort.exports.variants.FortCosmeticProfileBannerVariant
import me.fungames.jfortniteparse.fort.exports.variants.FortCosmeticRichColorVariant
import me.fungames.jfortniteparse.fort.exports.variants.FortCosmeticVariantBackedByArray
import me.fungames.jfortniteparse.util.printHexBinary
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import net.dv8tion.jda.api.interactions.components.selections.SelectMenu
import net.dv8tion.jda.api.interactions.components.selections.SelectMenuInteraction
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import kotlin.jvm.internal.Ref

val equipEmote by lazy { getEmoteByName("akl_equip") }
val editStylesEmote by lazy { getEmoteByName("akl_editStyles") }
val favoritedEmote by lazy { getEmoteByName("akl_favorited") }
val favoriteEmote by lazy { getEmoteByName("akl_favorite") }
val bangEmote by lazy { getEmoteByName("akl_new") }

class CosmeticCommand : BrigadierCommand("cosmetic", "Shows info and options about a BR cosmetic you own.", arrayOf("c")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("type", StringArgumentType.string())
			.then(argument("item", ItemArgument.item(true))
				.executes {
					val itemType = parseCosmeticType(StringArgumentType.getString(it, "type"))
					val source = it.source
					source.ensureSession()
					source.loading("Getting cosmetics")
					source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
					val athena = source.api.profileManager.getProfileData("athena")
					execute(it.source, ItemArgument.getItemWithFallback(it, "item", athena, itemType), athena)
				}
			)
		)

	override fun getSlashCommand() = newCommandBuilder()
		.option(OptionType.STRING, "type", "Type of the cosmetic to search", true, choices = COSMETIC_TYPE_CHOICES)
		.option(OptionType.STRING, "item", "Cosmetic to search for", true, argument = ItemArgument.item(true))
		.executes { source ->
			source.ensureSession()
			source.loading("Getting cosmetics")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			execute(source, source.getArgument<ItemArgument.Result>("item")!!.resolveWithFallback(athena, source.getOption("type")!!.asString), athena)
		}
}

class CampaignCosmeticCommand : BrigadierCommand("stwcosmetic", "Shows info and options about a BR cosmetic you own, and interact with STW locker instead of BR.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("type", StringArgumentType.string())
			.then(argument("item", ItemArgument.item(true))
				.executes {
					val itemType = parseCosmeticType(StringArgumentType.getString(it, "type"))
					val source = it.source
					source.ensureSession()
					source.loading("Getting cosmetics")
					CompletableFuture.allOf(
						source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena"),
						source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign")
					).await()
					val athena = source.api.profileManager.getProfileData("athena")
					val campaign = source.api.profileManager.getProfileData("campaign")
					execute(it.source, ItemArgument.getItemWithFallback(it, "item", athena, itemType), campaign)
				}
			)
		)

	override fun getSlashCommand() = newCommandBuilder()
		.option(OptionType.STRING, "type", "Type of the cosmetic to search", true, choices = COSMETIC_TYPE_CHOICES)
		.option(OptionType.STRING, "item", "Cosmetic to search for", true, argument = ItemArgument.item(true))
		.executes { source ->
			source.ensureSession()
			source.loading("Getting cosmetics")
			CompletableFuture.allOf(
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena"),
				source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "campaign")
			).await()
			val athena = source.api.profileManager.getProfileData("athena")
			val campaign = source.api.profileManager.getProfileData("campaign")
			execute(source, source.getArgument<ItemArgument.Result>("item")!!.resolveWithFallback(athena, source.getOption("type")!!.asString), campaign)
		}
}

private fun execute(source: CommandSourceStack, item: FortItemStack, profile: McpProfile, alert: String? = null): Int {
	val defData = item.defData as? AthenaCosmeticItemDefinition ?: throw SimpleCommandExceptionType(LiteralMessage("Not found")).create()
	val owned = item.itemId != null
	val embed = EmbedBuilder().setColor(item.palette.Color2.toColor())
		.setAuthor((item.defData?.Series?.value?.DisplayName ?: item.rarity.rarityName).format() + " \u00b7 " + item.shortDescription.format())
		.setTitle((if (item.isItemSeen) "" else bangEmote?.asMention + ' ') + (if (owned) "" else "Preview: ") + item.displayName.ifEmpty { defData.name })
		.setDescription(item.description)
		.setThumbnail(Utils.benBotExportAsset(item.getPreviewImagePath(true)?.toString()))
		.setFooter(defData.name)
	val buttons = mutableListOf<Button>()

	if (!owned) {
		if (defData.GameplayTags?.any { it.toString().startsWith("Cosmetics.Source.ItemShop", true) } == true) {
			embed.setFooter(defData.name + "\nWishlist and auto-buy coming soon!")
		}
		source.complete(alert, embed.build())
		return Command.SINGLE_SUCCESS
	}

	/*if (!owned) {
		val wishlistEnrollment = r.table("wishlist").get(source.api.currentLoggedIn.id).run(source.client.dbConn, WishlistEnrollment::class.java).first()
		val isInWishlist = wishlistEnrollment != null && !wishlistEnrollment.autoBuy
		val isInAutobuy = wishlistEnrollment != null && wishlistEnrollment.autoBuy

		if (isInWishlist) {
			buttons.add(Button.of(ButtonStyle.SECONDARY, "wishlist", "Remove from wishlist"))
		} else if (isInAutobuy) {
			buttons.add(Button.of(ButtonStyle.SECONDARY, "autobuy", "Unenroll auto-buy"))
		} else {
			buttons.add(Button.of(ButtonStyle.PRIMARY, "wishlist", "Wishlist"))
			buttons.add(Button.of(ButtonStyle.PRIMARY, "autobuy", "Auto-buy"))
		}

		val message = source.complete(alert, embed.build(), ActionRow.of(buttons))
		source.loadingMsg = message
		return when (message.awaitOneInteraction(source.author, false).componentId) {
			"wishlist" -> {
				return execute(source, item, profile)
			}
			else -> Command.SINGLE_SUCCESS
		}
	}*/

	// Prepare loadout stuff
	val loadoutItemId = Ref.ObjectRef<String>()
	val currentLoadout = FortCosmeticLockerItem.getFromProfile(profile, loadoutItemId)
		?: throw SimpleCommandExceptionType(LiteralMessage("Main preset not found. Must be a bug.")).create()
	val category = when (defData) {
		is AthenaCharacterItemDefinition -> EAthenaCustomizationCategory.Character
		is AthenaBackpackItemDefinition -> EAthenaCustomizationCategory.Backpack
		is AthenaPickaxeItemDefinition -> EAthenaCustomizationCategory.Pickaxe
		is AthenaGliderItemDefinition -> EAthenaCustomizationCategory.Glider
		is AthenaSkyDiveContrailItemDefinition -> EAthenaCustomizationCategory.SkyDiveContrail
		is AthenaDanceItemDefinition -> EAthenaCustomizationCategory.Dance
		is AthenaItemWrapDefinition -> EAthenaCustomizationCategory.ItemWrap
		is AthenaMusicPackItemDefinition -> EAthenaCustomizationCategory.MusicPack
		is AthenaLoadingScreenItemDefinition -> EAthenaCustomizationCategory.LoadingScreen
		else -> throw SimpleCommandExceptionType(LiteralMessage("Unsupported cosmetic type " + defData.exportType)).create()
	}

	// Check if equipped
	val numItems = category.numItems
	if (numItems == 1) {
		val isEquipped = currentLoadout.locker_slots_data.getSlotItems(category).firstOrNull() == item.templateId
		if (isEquipped) {
			buttons.add(Button.of(ButtonStyle.SECONDARY, "equip", "Equipped", Emoji.fromUnicode("✅")).asDisabled())
		} else {
			buttons.add(Button.of(ButtonStyle.SECONDARY, "equip", "Equip", equipEmote?.let(Emoji::fromEmote)))
		}
	} else {
		val equippedIndices = currentLoadout.locker_slots_data.getSlotItems(category).withIndex().filter { it.value == item.templateId }.map { it.index + 1 }
		if (equippedIndices.isNotEmpty()) {
			embed.setFooter("Equipped at slot(s) " + equippedIndices.joinToString(", "))
		}
		buttons.add(Button.of(ButtonStyle.SECONDARY, "equipTo", "Equip to...", equipEmote?.let(Emoji::fromEmote)))
	}

	// Prepare variants
	val variants = mutableListOf<VariantContainer>()
	defData.ItemVariants?.forEach { lazyVariant ->
		val cosmeticVariant = lazyVariant.value
		if (cosmeticVariant is FortCosmeticProfileBannerVariant) {
			return@forEach
		}
		variants.add(VariantContainer(cosmeticVariant, EpicApi.GSON.fromJson(item.attributes.getAsJsonArray("variants"), Array<McpVariantReader>::class.java) ?: emptyArray()))
	}
	if (variants.isNotEmpty()) {
		embed.addField("Styles", variants.joinToString("\n") {
			"%s: %s".format(it.channelName, it.activeVariantDisplayName)
		}, false)
		if (numItems == 1) { // TODO support editing variants for multiple slot items
			buttons.add(Button.of(ButtonStyle.SECONDARY, "editVariants", "Edit styles", editStylesEmote?.let(Emoji::fromEmote)))
		}
	}

	// Favorite button
	if (item.isFavorite) {
		buttons.add(Button.of(ButtonStyle.SUCCESS, "favorite", "Favorited", favoritedEmote?.let(Emoji::fromEmote)))
	} else {
		buttons.add(Button.of(ButtonStyle.SECONDARY, "favorite", "Favorite", favoriteEmote?.let(Emoji::fromEmote)))
	}

	// Mark seen button
	if (!item.isItemSeen) {
		buttons.add(Button.of(ButtonStyle.SECONDARY, "markSeen", "Mark seen", bangEmote?.let(Emoji::fromEmote)))
	}

	val message = source.complete(alert, embed.build(), ActionRow.of(buttons))
	source.loadingMsg = message
	return when (message.awaitOneInteraction(source.author, false).componentId) {
		"equip" -> equip(source, profile.profileId, loadoutItemId.element, category, item, 0)
		"equipTo" -> equipTo(source, profile.profileId, currentLoadout, loadoutItemId.element, category, item)
		"editVariants" -> editVariants(source, profile.profileId, loadoutItemId.element, category, item, variants)
		"favorite" -> toggleFavorite(source, item)
		"markSeen" -> markSeen(source, item)
		else -> Command.SINGLE_SUCCESS
	}
}

private fun equipTo(source: CommandSourceStack, profileId: String, currentLoadout: FortCosmeticLockerItem, lockerItem: String, category: EAthenaCustomizationCategory, item: FortItemStack, variantUpdates: Array<McpVariantReader>? = emptyArray()): Int {
	val numSlots = category.numItems
	// Slot <N> (<current item>)
	val buttons = mutableListOf<Button>()
	for (slotIndex in 0 until numSlots) {
		val currentItem = currentLoadout.locker_slots_data.slots[category]?.items?.getOrNull(slotIndex)
		val currentItemName = if (!currentItem.isNullOrEmpty()) FortItemStack(currentItem, 1).displayName.format() else "None"
		buttons.add(Button.of(ButtonStyle.PRIMARY, slotIndex.toString(), "Slot ${slotIndex + 1} ($currentItemName)"))
	}
	buttons.add(Button.of(ButtonStyle.PRIMARY, "-1", "All slots"))
	buttons.add(Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel", Emoji.fromUnicode("❌")))
	val message = source.complete("**To which slot?**", null, *buttons.chunked(5, ActionRow::of).toTypedArray())
	source.loadingMsg = message
	val choice = message.awaitOneInteraction(source.author, false).componentId
	if (choice == "cancel") {
		return execute(source, item, source.api.profileManager.getProfileData("athena"))
	}
	val slotIndex = choice.toIntOrNull() ?: return Command.SINGLE_SUCCESS
	return equip(source, profileId, lockerItem, category, item, slotIndex, variantUpdates)
}

private fun editVariants(source: CommandSourceStack, profileId: String, lockerItem: String, category: EAthenaCustomizationCategory, item: FortItemStack, variants: List<VariantContainer>): Int {
	if (variants.size == 1) {
		return editVariant(source, profileId, lockerItem, category, item, variants, variants.first())
	}
	val buttons = mutableListOf<Button>()
	for (variant in variants) {
		buttons.add(Button.of(ButtonStyle.PRIMARY, variant.cosmeticVariant.backendChannelName, "%s: %s".format(variant.channelName, variant.activeVariantDisplayName)))
	}
	buttons.add(Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel", Emoji.fromUnicode("❌")))
	val message = source.complete("**Select style to edit**", null, *buttons.chunked(5, ActionRow::of).toTypedArray())
	source.loadingMsg = message
	val choice = message.awaitOneInteraction(source.author, false).componentId
	if (choice == "cancel") {
		return execute(source, item, source.api.profileManager.getProfileData("athena"))
	}
	val variant = variants.firstOrNull { it.cosmeticVariant.backendChannelName == choice } ?: return Command.SINGLE_SUCCESS
	return editVariant(source, profileId, lockerItem, category, item, variants, variant)
}

private fun editVariant(source: CommandSourceStack, profileId: String, lockerItem: String, category: EAthenaCustomizationCategory, item: FortItemStack, variants: List<VariantContainer>, variant: VariantContainer): Int {
	val (cosmeticVariant, backendVariant) = variant
	val embed = EmbedBuilder().setColor(item.palette.Color2.toColor())
		.setTitle("✏ Editing: " + variant.channelName)
		.setDescription("**Current:** " + variant.activeVariantDisplayName)
	val selected = if (cosmeticVariant is FortCosmeticItemTexture) {
		val allowClear = cosmeticVariant.ItemTextureVar.bAllowClear ?: true
		embed.appendDescription(if (allowClear) "\nPick type or clear:" else "\nPick type:")
		val buttons = mutableListOf<Button>()
		buttons.add(Button.of(ButtonStyle.PRIMARY, "AthenaEmojiItemDefinition", "Emoticon"))
		buttons.add(Button.of(ButtonStyle.PRIMARY, "AthenaSprayItemDefinition", "Spray"))
		if (allowClear) {
			buttons.add(Button.of(ButtonStyle.PRIMARY, "clear", "Clear"))
		}
		buttons.add(Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel", Emoji.fromUnicode("❌")))
		val message = source.complete(null, embed.build(), ActionRow.of(buttons))
		source.loadingMsg = message
		val interaction = message.awaitOneInteraction(source.author, false)
		val choice = interaction.componentId
		if (choice == "cancel") {
			return execute(source, item, source.api.profileManager.getProfileData("athena"))
		}
		if (choice == "clear") {
			"ItemTexture.None"
		} else {
			val typeName = (interaction.component as Button).label
			source.loadingMsg = source.complete("Type in the exact name of the %s that you want to pick, or `cancel` to cancel:".format(typeName))
			val response = source.channel.awaitMessages({ collected, _, _ -> collected.author == source.author }, AwaitMessagesOptions().apply {
				max = 1
				time = 60000L
				errors = arrayOf(CollectorEndReason.TIME)
			}).await().first()
			val search = response.contentRaw
			if (source.guild?.selfMember?.hasPermission(Permission.MESSAGE_MANAGE) == true) {
				response.delete().queue()
			}
			if (search == "cancel") {
				return execute(source, item, source.api.profileManager.getProfileData("athena"))
			}
			source.loading("Searching for `$search`")
			val (_, itemDef) = searchItemDefinition(search, "AthenaDance", choice)
				?: return execute(source, item, source.api.profileManager.getProfileData("athena"), "⚠ No %s found for `%s`".format(typeName, search))
			"ItemTexture.AthenaDance:${itemDef.name.toLowerCase()}"
		}
	} else if (cosmeticVariant is FortCosmeticRichColorVariant) {
		val colors = cosmeticVariant.InlineVariant.RichColorVar.ColorSwatchForChoices?.load<CustomDynamicColorSwatch>()?.ColorPairs
			?: throw SimpleCommandExceptionType(LiteralMessage("No color swatch for %s. Custom colors are not yet supported.".format(variant.channelName))).create()
		val activeColor = cosmeticVariant.getActive(backendVariant)
		embed.setThumbnail("https://singlecolorimage.com/get/%06x/%dx%d".format(activeColor.toFColor(true).toPackedARGB() and 0xFFFFFF, 128, 128))
		val currentInSwatch = colors.findPair(activeColor)
		val options = colors.map {
			val colorName = it.ColorName.toString()
			var option = SelectOption.of(it.ColorDisplayName.format() ?: colorName, colorName).withDefault(it == currentInSwatch)

			// Check conflicts
			if (cosmeticVariant.AntiConflictChannel != -1 && (colorName == "BLACK" || colorName == "WHITE")) {
				for (otherVariant in variants) {
					if (otherVariant == variant) {
						continue
					}
					val otherRichColorVariant = otherVariant.cosmeticVariant as? FortCosmeticRichColorVariant ?: continue
					if (otherRichColorVariant.AntiConflictChannel != cosmeticVariant.AntiConflictChannel) {
						continue
					}
					val otherColors = otherRichColorVariant.InlineVariant.RichColorVar.ColorSwatchForChoices?.load<CustomDynamicColorSwatch>()?.ColorPairs
						?: continue
					val otherCurrentInSwatch = otherColors.findPair(otherRichColorVariant.getActive(otherVariant.backendVariant)) ?: continue
					if (otherCurrentInSwatch.ColorName == it.ColorName) {
						option = option.withDescription("This color is already in use by %s".format(otherVariant.channelName)).withEmoji(Emoji.fromUnicode("🚫"))
						break
					}
				}
			}
			option
		}
		val (selectedColorName, lockReason) = askChoice(source, embed, options, "Pick a new color to apply")
		val selectedColor = when (selectedColorName) {
			null -> return execute(source, item, source.api.profileManager.getProfileData("athena"))
			"__locked__" -> return execute(source, item, source.api.profileManager.getProfileData("athena"), "🚫 $lockReason")
			else -> colors.first { it.ColorName.toString() == selectedColorName }
		}
		val colorValue = selectedColor.ColorValue
		val buf = ByteBuffer.allocate(4 * 4)
		buf.putFloat(colorValue.r)
		buf.putFloat(colorValue.g)
		buf.putFloat(colorValue.b)
		buf.putFloat(colorValue.a)
		"RichColor." + buf.array().printHexBinary()
	} else if (cosmeticVariant is FortCosmeticVariantBackedByArray) {
		val options = mutableListOf<SelectOption>()
		val variantDefs = cosmeticVariant.variants ?: emptyList()
		for (variantDef in variantDefs) {
			val backendName = variantDef.backendVariantName
			var lockReason: String? = null
			if (!variantDef.bStartUnlocked) {
				val owned = backendVariant != null && backendVariant.owned.contains(backendName) // TODO might be better scanning variant token items
				if (!owned) {
					if (variantDef.bHideIfNotOwned) {
						continue
					}
					lockReason = variantDef.UnlockRequirements.format() ?: "Unknown"
				}
			}
			var variantDisplayName = variantDef.VariantName.format()
			if (variantDisplayName.isNullOrEmpty()) {
				variantDisplayName = backendName!!
			}
			var option = SelectOption.of(variantDisplayName, backendName)
			if (lockReason != null) {
				option = option.withDescription(lockReason.format().ifEmpty { null }).withEmoji(Emoji.fromEmote(lockEmote!!))
			}
			options.add(option)
		}
		val (selectedVariantName, lockReason) = askChoice(source, embed, options, "Pick a new style to apply")
		when (selectedVariantName) {
			null -> return execute(source, item, source.api.profileManager.getProfileData("athena"))
			"__locked__" -> return execute(source, item, source.api.profileManager.getProfileData("athena"), "🚫 $lockReason")
			else -> variantDefs.firstOrNull { it.backendVariantName == selectedVariantName }?.backendVariantName ?: return Command.SINGLE_SUCCESS
		}
	} else {
		throw SimpleCommandExceptionType(LiteralMessage("Unsupported ${cosmeticVariant.exportType} ${variant.channelName}. Channel tag is ${variant.cosmeticVariant.backendChannelName}.")).create()
	}
	return equip(source, profileId, lockerItem, category, item, 0, arrayOf(McpVariantReader(cosmeticVariant.backendChannelName, selected, emptyArray())))
}

fun askChoice(source: CommandSourceStack, embed: EmbedBuilder, options: List<SelectOption>, hint: String): Pair<String?, String?> {
	val selected = if (options.size > 25) {
		embed.addFieldSeparate(hint, options, inline = true) { (it.emoji?.asMention?.let { "$it " } ?: "") + it.label }
		embed.setFooter("Type the number of the option you want to select, or 'cancel' to return.")
		val message = source.complete(null, embed.build())
		source.loadingMsg = message
		val response = source.channel.awaitMessages({ msg, user, _ -> user == source.author && (msg.contentRaw.equals("cancel", true) || msg.contentRaw.toIntOrNull()?.let { it >= 1 && it <= options.size } == true) }, AwaitMessagesOptions().apply {
			max = 1
			time = 30000
			errors = arrayOf(CollectorEndReason.TIME, CollectorEndReason.MESSAGE_DELETE)
		}).await().first()
		val choice = message.contentRaw.toIntOrNull()
		if (source.guild?.selfMember?.hasPermission(Permission.MESSAGE_MANAGE) == true) {
			response.delete().queue()
		}
		if (choice == null) {
			return null to null
		}
		options[choice - 1]
	} else {
		val message = source.complete(null, embed.build(), ActionRow.of(SelectMenu.create("choice").setPlaceholder(hint).addOptions(options).build()), ActionRow.of(Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel", Emoji.fromUnicode("❌"))))
		source.loadingMsg = message
		val interaction = message.awaitOneInteraction(source.author, false)
		if (interaction.componentId == "cancel") {
			return null to null
		}
		(interaction as SelectMenuInteraction).selectedOptions.first()
	}
	if (selected.emoji != null) {
		return "__locked__" to selected.description
	}
	return selected.value to null
}

private fun equip(source: CommandSourceStack, profileId: String, inLockerItem: String, inCategory: EAthenaCustomizationCategory, item: FortItemStack, inSlotIndex: Int, inVariantUpdates: Array<McpVariantReader>? = emptyArray()): Int {
	source.api.profileManager.dispatchClientCommandRequest(SetCosmeticLockerSlot().apply {
		lockerItem = inLockerItem
		category = inCategory
		itemToSlot = item.templateId
		slotIndex = inSlotIndex
		variantUpdates = inVariantUpdates
		optLockerUseCountOverride = -1
	}, profileId).await()
	if (inCategory == EAthenaCustomizationCategory.Character && profileId == "athena") {
		source.session.avatarCache.remove(source.api.currentLoggedIn.id)
	}
	return execute(source, item, source.api.profileManager.getProfileData(profileId))
}

private fun toggleFavorite(source: CommandSourceStack, item: FortItemStack): Int {
	source.api.profileManager.dispatchClientCommandRequest(SetItemFavoriteStatus().apply {
		targetItemId = item.itemId
		bFavorite = !item.isFavorite
	}, "athena").await()
	return execute(source, item, source.api.profileManager.getProfileData("athena"))
}

private fun markSeen(source: CommandSourceStack, item: FortItemStack): Int {
	source.api.profileManager.dispatchClientCommandRequest(MarkItemSeen().apply {
		itemIds = arrayOf(item.itemId)
	}, "athena").await()
	return execute(source, item, source.api.profileManager.getProfileData("athena"))
}

private val EAthenaCustomizationCategory.numItems
	get() = when (this) {
		EAthenaCustomizationCategory.Dance -> 6
		EAthenaCustomizationCategory.ItemWrap -> 7
		else -> 1
	}