package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.images.GenerateLockerImageParams
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.awaitConfirmation
import com.tb24.discordbot.util.confirmationButtons
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.commands.subgame.SetItemFavoriteStatusBatch
import com.tb24.fn.util.getBoolean
import okhttp3.Request
import java.util.concurrent.CompletableFuture

enum class ExclusivesType {
	EXCLUSIVE, UNIQUE, RARE
}

class ExclusivesEntry(val itemDefName: String, val type: ExclusivesType, val reason: String)

val exclusives by lazy {
	val exclusivesCsvUrl = BotConfig.get().exclusivesCsvUrl
	if (exclusivesCsvUrl.isNullOrEmpty()) {
		throw SimpleCommandExceptionType(LiteralMessage("Exclusives data is not defined.")).create()
	}
	val response = DiscordBot.instance.okHttpClient.newCall(Request.Builder().url(exclusivesCsvUrl).build()).execute()
	if (!response.isSuccessful) {
		throw SimpleCommandExceptionType(LiteralMessage("Request failed with status code " + response.code())).create()
	}
	val lines = response.body()!!.charStream().use { it.readLines() }
	val result = hashMapOf<String, ExclusivesEntry>()
	lines.forEachIndexed { index, line ->
		if (index != 0) {
			val (templateId, typeInitial, reason) = line.split(",", limit = 3)
			val itemDefName = templateId.substringAfter(":").toLowerCase()
			val type = ExclusivesType.values().first { it.name.startsWith(typeInitial, true) }
			result[itemDefName] = ExclusivesEntry(itemDefName, type, reason)
		}
	}
	result
}

private fun getExclusiveItems(profiles: Collection<McpProfile>, onlyCosmetics: Boolean): List<FortItemStack> {
	val localExclusives = exclusives.values
	return profiles.flatMap { profile ->
		profile.items.values.filter { item -> localExclusives.any { it.itemDefName.equals(item.primaryAssetName, true) } && (!onlyCosmetics || item.primaryAssetType != "CosmeticVariantToken") }
	}
}

class ExclusivesCommand : BrigadierCommand("exclusives", "Shows your exclusive cosmetics in an image.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }
		.then(literal("favoriteall").executes { executeUpdateFavorite(it.source, true) })
		.then(literal("unfavoriteall").executes { executeUpdateFavorite(it.source, false) })

	override fun getSlashCommand() = newCommandBuilder()
		.then(subcommand("view", description).executes { execute(it) })
		.then(subcommand("favoriteall", "Favorites all of your exclusives.").executes { executeUpdateFavorite(it, true) })
		.then(subcommand("unfavoriteall", "Unfavorites all of your exclusives.").executes { executeUpdateFavorite(it, false) })

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		CompletableFuture.allOf(
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "common_core"),
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
		).await()
		val commonCore = source.api.profileManager.getProfileData("common_core")
		val athena = source.api.profileManager.getProfileData("athena")
		val items = getExclusiveItems(setOf(commonCore, athena), false)
		source.loading("Generating and uploading image")
		generateAndSendLockerImage(source, items, GenerateLockerImageParams("Exclusives", "/Game/Athena/UI/Frontend/Art/T_UI_BP_BattleStar_L.T_UI_BP_BattleStar_L", showExclusivesInfo = true)).await()
			?: throw SimpleCommandExceptionType(LiteralMessage("No Exclusives.")).create()
		return Command.SINGLE_SUCCESS
	}

	private fun executeUpdateFavorite(source: CommandSourceStack, favorite: Boolean): Int {
		source.ensureSession()
		source.loading("Getting cosmetics")
		source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
		val athena = source.api.profileManager.getProfileData("athena")
		val items = getExclusiveItems(setOf(athena), true)
		val toFavorite = items.filter { it.attributes.getBoolean("favorite") != favorite }
		if (toFavorite.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage(if (favorite) "Nothing to favorite." else "Nothing to unfavorite.")).create()
		}
		val embed = source.createEmbed().setColor(COLOR_WARNING)
			.setDescription((if (favorite) "Favorite **%,d** of **%,d** exclusive items?" else "Unfavorite **%,d** of **%,d** exclusive items?").format(toFavorite.size, items.size))
		val confirmationMsg = source.complete(null, embed.build(), confirmationButtons())
		if (!confirmationMsg.awaitConfirmation(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
		source.api.profileManager.dispatchClientCommandRequest(SetItemFavoriteStatusBatch().apply {
			itemIds = toFavorite.map { it.itemId }.toTypedArray()
			itemFavStatus = BooleanArray(toFavorite.size) { favorite }.toTypedArray()
		}, "athena").await()
		confirmationMsg.editMessageEmbeds(embed.setColor(COLOR_SUCCESS)
			.setDescription("✅ " + (if (favorite) "Favorited %,d exclusives!" else "Unfavorited %,d exclusives!").format(toFavorite.size))
			.build()).complete()
		return Command.SINGLE_SUCCESS
	}
}