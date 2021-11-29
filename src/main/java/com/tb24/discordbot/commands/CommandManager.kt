package com.tb24.discordbot.commands

import com.google.common.base.Throwables
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.tree.LiteralCommandNode
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.util.CollectorEndReason
import com.tb24.discordbot.util.CollectorException
import com.tb24.discordbot.util.EmbedMessage
import com.tb24.fn.network.AccountService.GrantType
import com.tb24.fn.util.EAuthClient
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.ChannelType
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.internal.utils.Helpers
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class CommandManager(private val client: DiscordBot) : ListenerAdapter() {
	@JvmField var dispatcher = CommandDispatcher<CommandSourceStack>()
	val commandMap = hashMapOf<String, BrigadierCommand>()
	val redirects = hashMapOf<String, BrigadierCommand>()
	private val threadPool = Executors.newCachedThreadPool()

	init {
		register(AboutCommand())
		register(AccoladesCommand())
		register(AccountCommand())
		register(AffiliateNameCommand())
		register(AthenaDailyChallengesCommand())
		register(AthenaInventoryCommand())
		register(AthenaLoadoutsCommand())
		register(AthenaOverviewCommand())
		register(AthenaQuestsCommand())
		register(AuthCodeCommand())
		register(AutoDailyRewardsCommand())
		//register(AvatarCommand())
		register(BattlePassCommand())
		register(BlurlCommand())
		register(CampaignLoadoutsCommand())
		register(CampaignOverviewCommand())
		register(CampaignShopCommand())
		register(CharacterCollectionCommand())
		register(CheckCodeCommand())
		register(ClaimMfaCommand())
		register(CloudStorageCommand())
		register(CollectionBookCommand())
		register(ColorCommand())
		register(CommandsCommand())
		register(CompetitiveCommand())
		register(ComposeMcpCommand())
		register(CosmeticCommand())
		register(CreativeCommand())
		register(CreativeXpCommand())
		register(DailyQuestsCommand())
		register(DailyRewardsCommand())
		register(DecompileCommand())
		register(DeleteMessageCommand())
		register(DeleteSavedLoginCommand())
		register(DeviceAuthCommand())
		register(DisassembleCommand())
		register(DumpAssetCommand())
		register(DumpClassCommand())
		register(DumpItemNamesCommand())
		register(DumpSaveGameCommand())
		register(EmbedCommand())
		register(EvalCommand())
		register(ExchangeCommand())
		register(ExclusivesCommand())
		register(ExecAutoDailyRewardsCommand())
		register(ExpeditionsCommand())
		register(ExportObjectCommand())
		register(ExtendedLoginCommand())
		register(FishCollectionCommand())
		register(FriendChestCommand())
		register(FriendsCommand())
		register(GiftCommand())
		register(GiftHistoryCommand())
		register(GiftSettingsCommand())
		register(GrantCommand())
		register(GrantFortniteAccessCommand())
		register(GrantRoledCommand())
		register(HelpCommand())
		register(HeroLoadoutCommand())
		register(HomebaseNameCommand())
		register(InfoCommand())
		register(InviteBotCommand())
		register(ItemCollectCommand())
		register(LaunchAndroidCommand())
		register(LaunchWindowsCommand())
		register(LibraryCommand())
		register(LockerCommand())
		register(LoginCommand())
		register(LogoutCommand())
		register(MemoryCommand())
		register(MilestonesCommand())
		register(MissionAlertsCommand())
		register(MtxAlertsCommand())
		register(MtxBalanceCommand())
		register(MtxPlatformCommand())
		register(NewsCommand())
		register(PhoenixCommand())
		register(PingCommand())
		register(PrefixCommand())
		register(PurchaseCommand())
		register(PurchasesCommand())
		register(QuestCommand())
		register(ReceiptsCommand())
		register(RedeemCodeCommand())
		register(RedeemPurchasesCommand())
		register(RefreshProfileCommand())
		register(ResearchCommand())
		register(ResourcesCommand())
		register(RevokeCommand())
		register(RollCommand())
		register(SaveLoginCommand())
		register(SchematicsCommand())
		register(ShopCommand())
		register(ShopDumpCommand())
		register(ShopTextCommand())
		register(SkipIntroCommand())
		register(StatsPrivacyCommand())
		register(StormShieldCommand())
		register(StwAccoladesCommand())
		register(SyncPremiumRoleCommand())
		register(UndoCommand())
		register(WorkersCommand())
		register(WorthCommand())
		register(FortniteAndroidApkCommand())
	}

	private fun register(command: BrigadierCommand): LiteralCommandNode<CommandSourceStack> {
		commandMap[command.name] = command
//		val node = command.getNode(dispatcher)
		val registered = command.register(dispatcher)
		for (alias in command.aliases) {
			redirects[alias] = command
			dispatcher.register(buildRedirect(alias, registered))
		}
		command.registeredNode = registered
		return registered
	}

	// Redirects only work for nodes with children, but break the top argument-less command.
	// Manually adding the root command after setting the redirect doesn't fix it.
	// See https://github.com/Mojang/brigadier/issues/46). Manually clone the node instead.
	private fun buildRedirect(alias: String, destination: LiteralCommandNode<CommandSourceStack>) =
		destination.children.fold(literal<CommandSourceStack>(alias)
			.requires(destination.requirement)
			.forward(destination.redirect, destination.redirectModifier, destination.isFork)
			.executes(destination.command)) { acc, it -> acc.then(it) }

	override fun onMessageReceived(event: MessageReceivedEvent) {
		threadPool.submit {
			val prefix = client.getCommandPrefix(event.message)
			if (event.author === client.discord.selfUser || event.author.isBot || !event.message.contentRaw.startsWith(prefix))
				return@submit

			handleCommand(event.message.contentRaw, CommandSourceStack(client, event.message, event.author.id), prefix)
		}
	}

	fun handleCommand(command: String, source: CommandSourceStack, prefix: String = "", canRetry: Boolean = true) {
		val reader = StringReader(command)
		reader.cursor += prefix.length
		while (reader.peek().isWhitespace()) {
			reader.skip()
		}

		try {
			val parseResults = dispatcher.parse(reader, source)
			val nodes = parseResults.context.nodes
			if (nodes.isEmpty()) { // Unknown command
				return
			}
			try {
				try {
					dispatcher.execute(parseResults)
				} catch (e: CollectorException) {
					if (e.reason == CollectorEndReason.TIME && source.isFromType(ChannelType.PRIVATE)) {
						throw SimpleCommandExceptionType(LiteralMessage("Timed out while waiting for your response.")).create()
					}
				}
			} catch (e: CommandSyntaxException) {
				val unkCmd = CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand()
				val unkArgs = CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument()
				val embed = EmbedBuilder().setColor(0xF04947)
				val lines = mutableListOf<String>()
				// command error message and optionally context
				if (e.type != unkCmd) {
					val rawMessage = e.rawMessage
					if (rawMessage is EmbedMessage) {
						source.complete(null, rawMessage.embed)
						return
					}
					lines.add("❌ " + rawMessage.string)
					// dupe of CommandSyntaxException.getContext() but with formatting
					if (e.input != null && e.cursor >= 0) {
						val builder = StringBuilder()
						val cursor = min(e.input.length, e.cursor)
						if (cursor > CommandSyntaxException.CONTEXT_AMOUNT) {
							builder.append("...")
						}
						builder.append(e.input.substring(max(0, cursor - CommandSyntaxException.CONTEXT_AMOUNT), cursor))
						if (cursor < e.input.length) {
							builder.append("__${e.input.substring(cursor)}__")
						}
						builder.append("*<--[HERE]*")
						lines.add(builder.toString())
					}
				}
				// usages
				if ((e.type == unkCmd || e.type == unkArgs) && nodes.isNotEmpty()) {
					val usageText = StringBuilder()
					val validCmdStr = StringBuilder(prefix)
					for (i in nodes.indices) {
						validCmdStr.append(nodes[i].range.get(reader))
						if (i < nodes.size - 1) {
							validCmdStr.append(' ')
						} else { // last node, append the usages
							val usages = dispatcher.getSmartUsage(nodes[i].node, source)
							if (usages.isEmpty()) {
								usageText.append(validCmdStr)
							} else {
								val iterator = usages.iterator()
								while (iterator.hasNext()) {
									usageText.append(validCmdStr).append(' ').append(iterator.next().value)
									if (iterator.hasNext()) {
										usageText.append('\n')
									}
								}
							}
						}
					}
					embed.addField("Usage", usageText.toString(), false)
				}
				source.complete(null, embed.setDescription(lines.joinToString("\n")).build())
			}
		} catch (e: HttpException) {
			if (httpError(source, e)) {
				if (canRetry) {
					handleCommand(command, source, prefix, false)
				} else {
					client.dlog("__**Attempted to repeat a command more than once**__\nUser: ${source.author.asMention}\n```\n${Throwables.getStackTraceAsString(e)}```", null)
					DiscordBot.LOGGER.error("Attempted to repeat a command more than once", e)
				}
			}
		} catch (e: Throwable) {
			val additional = "\nCommand: ${reader.string}\nProxy: ${source.session.api.okHttpClient.proxy()}"
			System.err.println("Unhandled exception while executing command$additional")
			e.printStackTrace()
			unhandledException(source, e, additional)
		}
	}

	/**
	 * @return true if the action should be executed again
	 */
	fun httpError(source: CommandSourceStack, e: HttpException, errorTitle: String = source.errorTitle!!): Boolean {
		val description: String?
		var footer = ""
		val host: String = e.response.request().url().host()
		val isEpicGames = host.endsWith("epicgames.com") || host.endsWith("fortnite.qq.com")
		if (isEpicGames) {
			val session = source.session
			if ((e.code() == HttpURLConnection.HTTP_UNAUTHORIZED || (e.code() == HttpURLConnection.HTTP_FORBIDDEN && e.epicError.errorCode == "com.epicgames.common.token_verify_failed") /*special case for events service*/) && session.api.userToken?.account_id != null) {
				val invalidToken = session.api.userToken
				session.api.userToken = null
				session.otherClientApis.clear()

				val savedDevice = client.savedLoginsManager.get(session.id, invalidToken.account_id)
				if (savedDevice != null) {
					// Attempt token renewal using device_auth
					try {
						doDeviceAuthLogin(source, savedDevice, sendMessages = false)
						source.session = source.initialSession
						return true
					} catch (e: HttpException) {
						httpError(source, e, "Failed to renew session using device auth")
					} catch (e: CommandSyntaxException) {
						source.complete(null, EmbedBuilder().setColor(0xF04947).setDescription("❌ " + e.rawMessage.string).build())
					}
				} else if (System.currentTimeMillis() < invalidToken.refresh_expires_at.time) {
					// Attempt token renewal using refresh_token
					try {
						source.session.login(source, GrantType.refreshToken(invalidToken.refresh_token), EAuthClient.getByClientId(invalidToken.client_id), false)
						source.session = source.initialSession
						return true
					} catch (e: HttpException) {
						if (e.epicError.errorCode != "errors.com.epicgames.account.auth_token.invalid_refresh_token") {
							httpError(source, e, "Failed to renew session using refresh token")
						}
					}
				}

				// No more ways to renew token, clear session and inform user
				session.clear()
				source.complete(null, EmbedBuilder().setColor(BrigadierCommand.COLOR_ERROR)
					.setTitle("🚫 Logged out")
					.setDescription("You have been logged out due to one of the following reasons:\n\u2022 Account logged in elsewhere.\n\u2022 Been more than 24 hours since login.\n\u2022 Logged in using exchange code or authorization code but the originating session has been logged out.\n\u2022 Logged in using a saved login but got it removed.\n\u2022 Account's password changed.\n\u2022 Password reset initiated by Epic Games.\n\nYou don't have a saved login for this account, so we cannot log you back in automatically.")
					.build())
				return false
			}
			val error = e.epicError
			description = error.displayText
			footer = (if (error.numericErrorCode != null) "/" + error.numericErrorCode else "") + (if (error.errorCode != null) "/" + error.errorCode else "")
		} else {
			description = e.responseStr
		}
		source.complete(null, EmbedBuilder().setColor(BrigadierCommand.COLOR_WARNING)
			.setTitle("⚠ $errorTitle")
			.setDescription(Helpers.truncate(description, MessageEmbed.TEXT_MAX_LENGTH))
			.setFooter(e.code().toString() + footer)
			.build())
		return false
	}

	fun unhandledException(source: CommandSourceStack, e: Throwable, additional: String) {
		source.complete(null, EmbedBuilder().setColor(BrigadierCommand.COLOR_ERROR)
			.setTitle("💥 Uh oh! That was unexpected!")
			.setDescription("An error has occurred and we're working to fix the problem!\nYou can [join our server](${BotConfig.get().homeGuildInviteLink}) and report it there if we failed to fix it in time!")
			.addField("Error", "```$e```", false)
			.build())
		if (DiscordBot.ENV == "prod" || DiscordBot.ENV == "stage") {
			client.dlog("""__**Error report**__
User: ${source.author.asMention}$additional
```
${Throwables.getStackTraceAsString(e)}```""", null)
		}
	}
}