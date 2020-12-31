package com.tb24.discordbot.commands

import com.google.common.collect.ImmutableMap
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.util.exec
import com.tb24.fn.EpicApi
import com.tb24.fn.model.launcher.ClientDetails
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.api.EmbedBuilder

class FortniteAndroidApkCommand : BrigadierCommand("apk", "Get an APK download link for the latest Fortnite Android.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			val api = EpicApi(source.client.okHttpClient) // create a new EpicApi instance to prevent race conditions with existing running commands
			source.loading("Getting the APK download link")
			val token = api.accountService.getAccessToken(EAuthClient.LAUNCHER_APP_CLIENT_2.asBasicAuthString(), "client_credentials", ImmutableMap.of("token_type", "eg1"), null).exec().body()!!
			api.userToken = token
			val assetResponse = api.launcherService.getItemBuild("Android", "4fe75bbc5a674f4f9b356b5c90567da5", "Fortnite", "Live", ClientDetails().apply {
				abis = arrayOf("arm64-v8a")
			}).exec().body()!!
			val element = assetResponse.elements.first()
			val manifestId = element.manifests.first().uri.substringAfterLast('/').substringBeforeLast('.')
			val manifestLink = "http://epicgames-download1.akamaized.net/Builds/Fortnite/Apk/$manifestId.apk"
			source.complete(null, EmbedBuilder()
				.setTitle("Here's your APK download")
				.addField("Build Version", element.buildVersion, false)
				.addField("Download Link", manifestLink, false)
				.build())
			api.accountService.killSession(api.userToken.access_token).exec()
			Command.SINGLE_SUCCESS
		}
}