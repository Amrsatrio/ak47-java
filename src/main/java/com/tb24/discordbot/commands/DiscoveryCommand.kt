package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.to
import com.tb24.fn.model.FortCmsData
import com.tb24.fn.util.format
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.ue4.assets.exports.UDataAsset
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import okhttp3.Request

class DiscoveryCommand : BrigadierCommand("discovery", "Discovery screen commands.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { it.hasPremium() }
		.then(literal("favorite")
			.then(argument("id", StringArgumentType.string())
				.executes { favorite(it.source, StringArgumentType.getString(it, "id")) }
			)
		) // TODO Other functions such as list and remove

	private fun favorite(source: CommandSourceStack, linkCode: String): Int {
		source.ensureSession()
		val result = if (CreativeCommand.MNEMONIC_PATTERN.matcher(linkCode).matches()) {
			// Creative code
			source.loading("Searching `$linkCode`")
			val linkData = try {
				source.api.linksService.queryLinkByMnemonic("fn", linkCode, null, null).exec().body()!!
			} catch (e: HttpException) {
				if (e.epicError.errorCode == "errors.com.epicgames.links.no_active_version") {
					throw SimpleCommandExceptionType(LiteralMessage("Island not found: `$linkCode`")).create()
				}
				throw e
			}
			source.loading("Favoriting island ${linkData.metadata.title}")
			source.api.discoveryService.addFavorite(source.api.currentLoggedIn.id, linkCode).exec()
			val embed = source.createEmbed().setColor(COLOR_SUCCESS)
				.populateCreativeLink(linkData)
				.setTitle("✅ Favorited: ${linkData.metadata.title}")
			embed
		} else if (linkCode.startsWith("Playlist_", true)) {
			// Playlist
			val playlist = loadObject<UDataAsset>(AssetManager.INSTANCE.assetRegistry.lookup("FortPlaylistAthena", linkCode)?.objectPath)
				?: throw SimpleCommandExceptionType(LiteralMessage("No playlist found with name `$linkCode`.")).create()
			val playlistDisplayName = playlist.getOrNull<FText>("UIDisplayName")?.format() ?: playlist.name
			val playlistDescription = playlist.getOrNull<FText>("UIDescription")?.format()
			source.loading("Favoriting playlist `$playlistDisplayName`")
			source.api.discoveryService.addFavorite(source.api.currentLoggedIn.id, playlist.name.lowercase()).exec()
			val embed = source.createEmbed().setColor(COLOR_SUCCESS)
				.setTitle("✅ Favorited: $playlistDisplayName")
			if (!playlistDescription.isNullOrEmpty()) {
				embed.setDescription(playlistDescription)
			}
			val cmsData = source.api.okHttpClient.newCall(Request.Builder().url("https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game").build()).exec().to<FortCmsData>()
			cmsData.playlistinformation?.playlist_info?.playlists?.firstOrNull { it.playlist_name == playlist.name }?.let {
				embed.setImage(it.image)
			}
			embed
		} else if (linkCode.equals("campaign", true)) {
			// Subgame
			source.loading("Favoriting Save the World")
			source.api.discoveryService.addFavorite(source.api.currentLoggedIn.id, "campaign").exec()
			val embed = source.createEmbed().setColor(COLOR_SUCCESS)
				.setTitle("✅ Favorited: Save the World")
			embed
		} else {
			throw SimpleCommandExceptionType(LiteralMessage("Invalid link code: `$linkCode`. Supported link codes are:\n\u2022 Island: `0000-0000-0000`\n\u2022 Playlist: `Playlist_DefaultSquad` (case insensitive)\n\u2022 Save the World: `campaign`")).create()
		}
		source.complete(null, result.build())
		return Command.SINGLE_SUCCESS
	}
}