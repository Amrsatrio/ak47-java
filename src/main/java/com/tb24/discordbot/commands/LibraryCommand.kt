package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.StringUtil
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.replyPaginated
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.api.MessageBuilder

class LibraryCommand : BrigadierCommand("library", "Shows your Epic Games Store library.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting your Epic Games Store library")
			val launcherApi = source.session.getApiForOtherClient(EAuthClient.LAUNCHER_APP_CLIENT_2)
			val records = launcherApi.libraryService.queryItems(null, null, "ue").exec().body()!!.records
			val playtimes = launcherApi.libraryService.queryAllPlaytime(launcherApi.currentLoggedIn.id).exec().body()!!
			val items = launcherApi.catalogService.queryItemsBulk(records.map { it.catalogItemId }, false, false, "US", "en").exec().body()!!
			val entries = records.filter { items[it.catalogItemId]?.mainGameItem == null }.sortedBy { items[it.catalogItemId]?.title }
			source.message.replyPaginated(entries, 30, source.loadingMsg) { content, page, pageCount ->
				val entriesStart = page * 30 + 1
				val entriesEnd = entriesStart + content.size
				val value = content.joinToString("\n") { record ->
					val item = items[record.catalogItemId]
					val sb = StringBuilder(item?.title ?: record.sandboxName)
					val playtime = playtimes.firstOrNull { it.artifactId == record.appName }
					if (playtime != null) {
						sb.append(" \u2014 ").append(StringUtil.formatElapsedTime(playtime.totalTime * 1000L, false))
					}
					sb.toString()
				}
				val embed = source.createEmbed()
					.setTitle("Library")
					.setDescription("Showing %,d to %,d of %,d entries\n\n%s".format(entriesStart, entriesEnd - 1, entries.size, value))
					.setFooter("Page %,d of %,d".format(page + 1, pageCount))
				MessageBuilder(embed).build()
			}
			Command.SINGLE_SUCCESS
		}
}