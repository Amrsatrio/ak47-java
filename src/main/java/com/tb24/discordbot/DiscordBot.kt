package com.tb24.discordbot

import com.fasterxml.jackson.annotation.JsonInclude
import com.google.common.base.Throwables
import com.rethinkdb.RethinkDB.r
import com.rethinkdb.net.Connection
import com.rethinkdb.utils.Internals
import com.tb24.discordbot.commands.CommandManager
import com.tb24.discordbot.commands.OnlyChannelCommandSource
import com.tb24.discordbot.commands.executeShopImage
import com.tb24.discordbot.commands.executeShopText
import com.tb24.discordbot.managers.CatalogManager
import com.tb24.discordbot.managers.SavedLoginsManager
import com.tb24.discordbot.tasks.AutoLoginRewardTask
import com.tb24.discordbot.tasks.KeychainTask
import com.tb24.fn.model.assetdata.ESubGame
import com.tb24.uasset.AssetManager
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.GatewayIntent
import net.jodah.expiringmap.ExpirationPolicy
import net.jodah.expiringmap.ExpiringMap
import okhttp3.OkHttpClient
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.jvm.JvmField as F

class DiscordBot(token: String) {
	companion object {
		const val VERSION = "6.4.7"
		@F val LOGGER: Logger = LoggerFactory.getLogger("DiscordBot")
		@F val ENV: String = System.getProperty("env", "dev")
		lateinit var instance: DiscordBot

		/** Provides safe access to [instance] in places where bot initialization is optional */
		fun getInstanceOrNull() = if (::instance.isInitialized) instance else null

		@JvmStatic
		fun main(args: Array<String>) {
			val token = BotConfig.get().token
			if (token.isNullOrEmpty()) {
				LOGGER.warn("No token provided")
				exitProcess(1)
			}
			LOGGER.info("Starting Discord Bot...")
			if (BotConfig.get().loadGameFiles) {
				AssetManager.INSTANCE.loadPaks(false, 0)
			}
			try {
				instance = DiscordBot(token)
			} catch (e: Throwable) {
				LOGGER.error("Initialization failure", e)
				exitProcess(1)
			}
		}
	}

	var dbConn: Connection
	var savedLoginsManager: SavedLoginsManager

	var okHttpClient: OkHttpClient
	var proxyManager: ProxyManager
	var catalogManager: CatalogManager

	var discord: JDA
	var commandManager: CommandManager

	val sessions: MutableMap<String, Session> = ExpiringMap.builder()
		.expiration(40, TimeUnit.MINUTES)
		.expirationPolicy(ExpirationPolicy.ACCESSED)
		.build()
	lateinit var internalSession: Session

	val prefixMap = hashMapOf<Long, PrefixConfig>()

	val autoLoginRewardTask = AutoLoginRewardTask(this)
	val keychainTask = KeychainTask(this)
	private val scheduledExecutor = ScheduledThreadPoolExecutor(2)
	val scheduler = StdSchedulerFactory.getDefaultScheduler()

	init {
		// Init Quartz scheduler
		scheduler.start()
		Runtime.getRuntime().addShutdownHook(Thread { // TODO properly register the shutdown hook plugin
			scheduler.shutdown()
		})

		// Setup database
		val dbUrl = "rethinkdb://localhost:28015/ak47"
		LOGGER.info("Connecting to database {}...", dbUrl)
		Internals.getInternalMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
		dbConn = r.connection(dbUrl).connect()

		// DAOs
		savedLoginsManager = SavedLoginsManager(dbConn)

		// Setup APIs
		/*val port = System.getProperty("apiPort")
		if (port != null) {
			ApiServerKt.main(arrayOf("", port))
		}*/
		okHttpClient = OkHttpClient()
		proxyManager = ProxyManager()
		setupInternalSession()
		if (BotConfig.get().loadGameFiles) {
			// Load encrypted PAKs
			keychainTask.run()
		}
		catalogManager = CatalogManager()

		// Setup JDA
		LOGGER.info("Connecting to Discord...")
		val builder = JDABuilder.createDefault(token).setHttpClient(okHttpClient)
		if (ENV == "prod" || ENV == "stage") {
			builder.enableIntents(GatewayIntent.GUILD_MEMBERS)
		}
		discord = builder.build()
		commandManager = CommandManager(this)
		discord.addEventListener(commandManager)
		//discord.addEventListener(new ReactionHandler(this)); // TODO doesn't respond if the channel hasn't been interacted with
		discord.addEventListener(GuildListeners(this))
		discord.awaitReady()
		LOGGER.info("Logged in as {}! v{}", discord.selfUser.asTag, VERSION)
		Runtime.getRuntime().addShutdownHook(Thread {
			internalSession.logout()
		})
		discord.presence.activity = Activity.playing(".help \u00b7 v$VERSION")

		// Schedule tasks
		if (ENV != "dev") {
			scheduleUtcMidnightTask()
			if (BotConfig.get().loadGameFiles) {
				scheduleKeychainTask()
			}
		}
		catalogManager.initialize(this)
	}

	// region Scheduled tasks

	/** Schedules item shop poster and auto daily at 00:00 UTC */
	private fun scheduleUtcMidnightTask() {
		val now = ZonedDateTime.now(ZoneOffset.UTC)
		var nextRun = now.withHour(0).withMinute(0).withSecond(30)
		if (now > nextRun) {
			nextRun = nextRun.plusDays(1)
		}
		val task = Runnable {
			/*try {
				postItemShop()
			} catch (e: Throwable) {
				dlog("__**Failed to auto post item shop**__\n```\n${Throwables.getStackTraceAsString(e)}```", null)
				return@Runnable
			}*/
			try {
				autoLoginRewardTask.run()
			} catch (e: Throwable) {
				dlog("__**AutoLoginRewardTask failure**__\n```\n${Throwables.getStackTraceAsString(e)}```", null)
				AutoLoginRewardTask.TASK_IS_RUNNING.set(false)
			}
		}
		scheduledExecutor.scheduleAtFixedRate(task, Duration.between(now, nextRun).seconds, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS)
	}

	/** Decoupled and public, so you can manually invoke this through eval */
	@Suppress("MemberVisibilityCanBePrivate")
	fun postItemShop() {
		if (ENV == "dev") {
			//return
		}
		ensureInternalSession()
		val itemShopChannel = discord.getTextChannelById(BotConfig.get().itemShopChannelId)
		if (itemShopChannel != null) {
			executeShopText(OnlyChannelCommandSource(this, itemShopChannel), ESubGame.Athena)
			executeShopImage(OnlyChannelCommandSource(this, itemShopChannel))
		}
	}

	private fun scheduleKeychainTask() {
		val interval = 15L * 60L * 1000L
		val timeUntilNext = interval - System.currentTimeMillis() % interval
		scheduledExecutor.scheduleAtFixedRate({
			try {
				keychainTask.run()
			} catch (e: Throwable) {
				dlog("__**Keychain task failure**__\n```\n${Throwables.getStackTraceAsString(e)}```", null)
				AutoLoginRewardTask.TASK_IS_RUNNING.set(false)
			}
		}, timeUntilNext, interval, TimeUnit.MILLISECONDS)
	}
	// endregion

	// region Session manager
	fun setupInternalSession() {
		if (!::internalSession.isInitialized) {
			internalSession = getSession("__internal__")
		}
		val internalDeviceData = savedLoginsManager.getAll("__internal__")[0]
		try {
			internalSession.login(null, internalDeviceData.generateAuthFields(), internalDeviceData.authClient, false)
			LOGGER.info("Logged in to internal account: {} {}", internalSession.api.currentLoggedIn.displayName, internalSession.api.currentLoggedIn.id)
		} catch (e: IOException) {
			e.printStackTrace()
		}
	}

	@Synchronized
	fun ensureInternalSession() {
		val response = internalSession.api.accountService.verify(null).execute()
		if (response.code() == 401) {
			setupInternalSession()
		} else if (response.code() != 200) {
			throw HttpException(response)
		}
	}

	fun getSession(id: String) = sessions.getOrPut(id) { Session(this, id) }
	// endregion

	// region Prefix manager
	fun getCommandPrefix(message: Message): String {
		if (!message.isFromGuild /*|| ENV == "dev"*/) {
			return BotConfig.get().defaultPrefix
		}
		val guildId = message.guild.idLong
		var dbEntry = prefixMap[guildId]
		if (dbEntry == null) {
			val guildIdString = java.lang.Long.toUnsignedString(guildId)
			dbEntry = r.table("prefix")[guildIdString].run(dbConn, PrefixConfig::class.java).first()
			if (dbEntry == null) {
				dbEntry = PrefixConfig()
				dbEntry.server = guildIdString
				dbEntry.prefix = BotConfig.get().defaultPrefix
			}
			prefixMap[guildId] = dbEntry
		}
		return dbEntry.prefix
	}

	class PrefixConfig {
		lateinit var server: String
		lateinit var prefix: String
	}
	// endregion

	fun dlog(content: String?, embed: MessageEmbed?) {
		val logsChannel = discord.getTextChannelById(BotConfig.get().logsChannelId)
		if (logsChannel != null) {
			val builder = MessageBuilder(content)
			if (embed != null) {
				builder.setEmbeds(embed)
			}
			logsChannel.sendMessage(builder.build()).queue()
		}
	}

	val isProd get() = ENV != "dev" && discord.selfUser.idLong == 563753712376479754L
}