package com.tb24.discordbot.commands

import com.google.gson.JsonObject
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.dispatchClientCommandRequest
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.to
import com.tb24.fn.EpicApi
import com.tb24.fn.model.FortCmsData.*
import com.tb24.fn.model.PrmPayload
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.model.mcpprofile.stats.AthenaProfileStats
import com.tb24.fn.model.mcpprofile.stats.CommonCoreProfileStats
import net.dv8tion.jda.api.EmbedBuilder
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.CompletableFuture

class NewsCommand : BrigadierCommand("news", "Shows the in-game news.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { executePrm(it.source) }
		.then(literal("stw").executes { executeCms(it.source, "savetheworldnews") })
		.then(literal("br").executes { executePrm(it.source) })
		.then(literal("brraw").executes { executePrm(it.source, false) })
		.then(literal("creative").executes { executeCms(it.source, "creativenewsv2") })

	private fun executeCms(source: CommandSourceStack, name: String): Int {
		source.loading("Getting news")
		val cmsResponse = source.client.okHttpClient.newCall(Request.Builder().url("https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game/$name").build()).exec().to<NewsData>()
		if (!cmsResponse.news.motds.isNullOrEmpty()) {
			displayNews(cmsResponse.news.motds, source)
		} else {
			displayNews(cmsResponse.news.messages, source)
		}
		return Command.SINGLE_SUCCESS
	}

	private fun executePrm(source: CommandSourceStack, personalized: Boolean = true): Int {
		source.ensureSession()
		source.loading("Getting news")
		val completeAccountData = source.api.accountService.getById(source.api.currentLoggedIn.id).exec().body()!!
		CompletableFuture.allOf(
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile()),
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena")
		).await()
		val commonCore = source.api.profileManager.getProfileData("common_core")
		val commonCoreAttrs = commonCore.stats as CommonCoreProfileStats
		val athena = source.api.profileManager.getProfileData("athena")
		val attrs = athena.stats as AthenaProfileStats
		val p = PrmPayload()
		if (personalized) {
			p.platform = "Windows"
			p.language = "en"
			p.country = completeAccountData.country
			//p.serverRegion
			p.subscription = !commonCoreAttrs.subscriptions.isNullOrEmpty()
			p.battlepass = attrs.book_purchased
			p.battlepassLevel = attrs.book_level
		}
		val prmResponse = source.api.okHttpClient.newCall(Request.Builder()
			.url("https://prm-dialogue-public-api-prod.edea.live.use1a.on.epicgames.com/api/v1/fortnite-br/surfaces/motd/target")
			.post(RequestBody.create(MediaType.get("application/json"), EpicApi.GSON.toJson(p)))
			.build()).exec().to<JsonObject>()
		val motds = prmResponse.getAsJsonArray("contentItems").map { EpicApi.GSON.fromJson(it.asJsonObject.getAsJsonObject("contentFields"), CommonUISimpleMessageMOTD::class.java) }
		displayNews(motds, source)
		return Command.SINGLE_SUCCESS
	}

	private fun displayNews(motds: List<CommonUISimpleMessageBase>, source: CommandSourceStack) {
		if (motds.any { it is CommonUISimpleMessageMOTD && it.entryType == "Item" }) {
			source.session = source.client.internalSession
			source.client.catalogManager.ensureCatalogData(source.api)
		}
		motds.forEach { source.complete(null, it.createEmbed(source).build()) }
	}
}

fun CommonUISimpleMessageBase.createEmbed(source: CommandSourceStack): EmbedBuilder {
	val embed = EmbedBuilder().setColor(BrigadierCommand.COLOR_INFO)
		.setAuthor(adspace)
		.setTitle(title)
		.setDescription(body)
		.setImage(image.url)
	if (this is CommonUISimpleMessageMOTD) {
		embed.setAuthor(tabTitleOverride)
		val extras = mutableListOf<String>()
		if (!videoUID.isNullOrEmpty()) {
			extras.add("[%s](https://cdn.fortnite-api.com/streams/%s/%s.mp4)".format(
				if (!videoButtonText.isNullOrEmpty()) videoButtonText else "Watch Video",
				videoUID,
				"en"
			))
		}
		if (entryType == "Item" && !offerId.isNullOrEmpty()) {
			val offer = source.client.catalogManager.purchasableCatalogEntries.firstOrNull { it.offerId == offerId }
			if (offer != null) {
				extras.add("%s - `%sb %s`".format(
					if (!buttonTextOverride.isNullOrEmpty()) buttonTextOverride else offerButtonText ?: "View Item",
					source.prefix,
					if (offer.__ak47_index != -1) (offer.__ak47_index + 1).toString() else offer.offerId
				))
			}
		}
		if (entryType == "Website" && !websiteURL.isNullOrEmpty()) {
			var url = websiteURL
			@Suppress("HttpUrlsUsage")
			if (!url.startsWith("http://") && !url.startsWith("https://")) {
				url = "https://$url"
			}
			extras.add("[%s](%s)".format(
				if (!buttonTextOverride.isNullOrEmpty()) buttonTextOverride else websiteButtonText ?: "Open Website",
				url
			))
		}
		if (extras.isNotEmpty()) {
			embed.appendDescription("\n")
			extras.forEach { embed.appendDescription('\n' + it) }
		}
	}
	return embed
}