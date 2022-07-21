package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.await
import com.tb24.discordbot.util.exec
import com.tb24.fn.util.getInt
import com.tb24.fn.util.getString
import java.util.concurrent.CompletableFuture

class RafCommand : BrigadierCommand("referafriend", "View your Refer A Friend completion", arrayOf("raf")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { execute(it.source) }

	private fun execute(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Connecting")
		source.api.accountService.verify(false).exec()
		val webCampaign = source.session.getWebCampaignManager("referafriend")
		CompletableFuture.allOf(
			webCampaign.send("competition.get"),
			webCampaign.send("participant.get"),
		).await()
		val embed = source.createEmbed().setTitle(webCampaign.localization.getString("Metadata.open.title"))
		val competition = webCampaign.states["epic:epic.rafCompetition"]!!
		val participant = webCampaign.states[source.api.currentLoggedIn.id + ":epic.participant"]!!
		webCampaign.disconnect()
		for (p in participant["friendConnections"].asJsonArray) {
			val po = p.asJsonObject
			val pdn = po.getString("withDisplayName", "")
			val progressObject = po["taskProgress"].asJsonObject
			val progressDesc = if (po["campaignCompleted"].asBoolean) "✅ Campaign completed" else {
				val task = competition["tasks"].asJsonArray.first { it.asJsonObject.getString("taskId") == progressObject.getString("taskId") }.asJsonObject
				val title = task.getAsJsonArray("taskTitle").first { it.asJsonObject.getString("languageCode").equals("en-US", true) }.asJsonObject.getString("content")
				val progressNeeded = task.getInt("requiredProgress")
				val progressLocal = progressObject.getInt("progressLocal")
				val progressRemote = progressObject.getInt("progressRemote")
				"⠀**Task:** $title\n⠀**Progress**: You: $progressLocal/$progressNeeded, Them: $progressRemote/$progressNeeded"
			}
			embed.addField(pdn, progressDesc, false)
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}
