package com.tb24.discordbot

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.rethinkdb.RethinkDB.r
import com.rethinkdb.net.Connection
import com.rethinkdb.utils.Internals
import com.tb24.discordbot.commands.*
import com.tb24.discordbot.managers.CatalogManager
import com.tb24.discordbot.managers.SavedLoginsManager
import com.tb24.discordbot.tasks.AutoLoginRewardTask
import com.tb24.discordbot.tasks.KeychainTask
import com.tb24.discordbot.util.createRequest
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.getStackTraceAsString
import com.tb24.fn.DefaultInterceptor
import com.tb24.fn.model.assetdata.ESubGame
import com.tb24.fn.model.launcher.ClientDetails
import com.tb24.fn.util.EAuthClient
import com.tb24.uasset.AssetManager
import me.fungames.jfortniteparse.ue4.io.TOC_READ_OPTION_READ_DIRECTORY_INDEX
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder
import net.dv8tion.jda.api.sharding.ShardManager
import net.jodah.expiringmap.ExpirationPolicy
import net.jodah.expiringmap.ExpiringMap
import okhttp3.OkHttpClient
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
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
		const val VERSION = "6.6.0"
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
			try {
				instance = DiscordBot(token)
			} catch (e: Throwable) {
				LOGGER.error("Initialization failure", e)
				exitProcess(1)
			}
		}
	}

	val dbConn: Connection
	//val mongoClient: MongoClient
	//val datastore: Datastore
	val savedLoginsManager: SavedLoginsManager

	val okHttpClient: OkHttpClient
	val proxyManager: ProxyManager
	val catalogManager: CatalogManager

	val discord: ShardManager
	val commandManager: CommandManager

	val sessions: MutableMap<String, Session> = ExpiringMap.builder()
		.expiration(BotConfig.get().sessionLifetimeMinutes, TimeUnit.MINUTES)
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
		val dbUrl = BotConfig.get().rethinkUrl
		LOGGER.info("Connecting to database {}...", dbUrl)
		val mapper = Internals.getInternalMapper()
		mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
		mapper.setVisibility(mapper.serializationConfig.defaultVisibilityChecker
			.withFieldVisibility(JsonAutoDetect.Visibility.ANY)
			.withGetterVisibility(JsonAutoDetect.Visibility.NONE)
			.withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
			.withSetterVisibility(JsonAutoDetect.Visibility.NONE)
			.withCreatorVisibility(JsonAutoDetect.Visibility.NONE))
		dbConn = r.connection(dbUrl).connect()

		// Setup MongoDB
		/*val mongoUrl = BotConfig.get().mongoUrl
		LOGGER.info("Connecting to MongoDB database {}...", mongoUrl)
		mongoClient = MongoClients.create(mongoUrl)

		datastore = Morphia.createDatastore(mongoClient, "ak47")
		datastore.mapper.mapPackage("com.tb24.discordbot.model")
		datastore.ensureIndexes()*/

		// DAOs
		savedLoginsManager = SavedLoginsManager(dbConn)

		// Setup APIs
		/*val port = System.getProperty("apiPort")
		if (port != null) {
			ApiServerKt.main(arrayOf("", port))
		}*/
		okHttpClient = OkHttpClient()
		proxyManager = ProxyManager()
		SessionPersister.client = this
		setupInternalSession()

		// Check for latest build
		LOGGER.info("Checking latest Fortnite build...")
		val launcherAppApi = internalSession.getApiForOtherClient(EAuthClient.LAUNCHER_APP_CLIENT_2)
		val assetResponse = launcherAppApi.launcherService.querySignedDownload("Windows", "4fe75bbc5a674f4f9b356b5c90567da5", "Fortnite", "Live", ClientDetails()).exec().body()!!
		val element = assetResponse.elements.first()
		val buildVersion = element.buildVersion
		val version = buildVersion.substringBeforeLast('-')
		DefaultInterceptor.userAgent = "Fortnite/%s Android/12".format(version)
		LOGGER.info("Fortnite version: $version")
		val loadGameFiles = BotConfig.get().loadGameFiles

		// Prepare manifest
		if (loadGameFiles == BotConfig.EGameFileLoadOption.Streamed) {
			val manifestsDir = File("manifests")
			if (!manifestsDir.exists()) {
				manifestsDir.mkdirs()
			}
			val chunkDownloadDir = File("chunkDownload")
			if (!chunkDownloadDir.exists()) {
				chunkDownloadDir.mkdirs()
			}
			val downloadInfo = element.manifests.first()
			val manifestFileName = downloadInfo.uri.substringAfterLast('/')
			val manifestFile = File(manifestsDir, manifestFileName)
			if (!manifestFile.exists()) {
				val manifestRequest = downloadInfo.createRequest()
				LOGGER.info("Downloading manifest: " + manifestRequest.url())
				val manifestResponse = okHttpClient.newCall(manifestRequest).exec().body()!!
				manifestFile.writeBytes(manifestResponse.bytes())
			} else {
				LOGGER.info("Using existing manifest")
			}
			System.setProperty("manifestFile", manifestFile.path)
		} else if (loadGameFiles == BotConfig.EGameFileLoadOption.Local) {
			LOGGER.info("Using local game installation")
		}

		if (loadGameFiles != BotConfig.EGameFileLoadOption.NoLoad) {
			AssetManager.INSTANCE.loadPaks(false, if (ENV == "prod") TOC_READ_OPTION_READ_DIRECTORY_INDEX else 0)
			keychainTask.run() // Load encrypted PAKs
		}
		catalogManager = CatalogManager()

		// Setup JDA
		LOGGER.info("Connecting to Discord...")
		val builder = DefaultShardManagerBuilder.createDefault(token).setHttpClient(okHttpClient)
		if (ENV == "prod" || ENV == "stage") {
			builder.enableIntents(GatewayIntent.GUILD_MEMBERS)
		}
		discord = builder.build()
		commandManager = CommandManager(this)
		discord.addEventListener(commandManager)
		discord.addEventListener(GuildListeners(this))
		//LOGGER.info("Logged in as {}! v{}", discord.selfUser.asTag, VERSION)
		Runtime.getRuntime().addShutdownHook(Thread {
			internalSession.logout()
		})
		discord.setActivityProvider { Activity.playing(".help \u00b7 $it \u00b7 v$VERSION") }

		// Schedule tasks
		if (ENV != "dev") {
			scheduleUtcMidnightTask()
			if (loadGameFiles != BotConfig.EGameFileLoadOption.NoLoad) {
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
				dlog("__**Failed to auto post item shop**__\n```\n${e.getStackTraceAsString()}```", null)
				return@Runnable
			}*/
			try {
				postMtxAlerts()
			} catch (e: Throwable) {
				dlog("__**Failed to auto post V-Bucks alerts**__\n```\n${e.getStackTraceAsString()}```", null)
				return@Runnable
			}
			try {
				autoLoginRewardTask.run()
			} catch (e: Throwable) {
				dlog("__**AutoLoginRewardTask failure**__\n```\n${e.getStackTraceAsString()}```", null)
				AutoLoginRewardTask.TASK_IS_RUNNING.set(false)
			}
		}
		scheduledExecutor.scheduleAtFixedRate(task, Duration.between(now, nextRun).seconds, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS)
	}

	/** Decoupled and public, so you can manually invoke this through eval */
	@Suppress("MemberVisibilityCanBePrivate")
	fun postItemShop() {
		val itemShopChannel = discord.getTextChannelById(BotConfig.get().itemShopChannelId)
		if (itemShopChannel != null) {
			ensureInternalSession()
			val source = OnlyChannelCommandSource(this, itemShopChannel)
			executeShopText(source, ESubGame.Athena)
			executeShopImage(source)
		}
	}

	@Suppress("MemberVisibilityCanBePrivate")
	fun postMtxAlerts() {
		val mtxAlertsChannel = discord.getTextChannelById(BotConfig.get().mtxAlertsChannelId)
		if (mtxAlertsChannel != null) {
			val source = OnlyChannelCommandSource(this, mtxAlertsChannel)
			executeMtxAlerts(source)
		}
	}

	private fun scheduleKeychainTask() {
		val interval = 15L * 60L * 1000L
		val timeUntilNext = interval - System.currentTimeMillis() % interval
		scheduledExecutor.scheduleAtFixedRate({
			try {
				keychainTask.run()
			} catch (e: Throwable) {
				dlog("__**Keychain task failure**__\n```\n${e.getStackTraceAsString()}```", null)
				AutoLoginRewardTask.TASK_IS_RUNNING.set(false)
			}
		}, timeUntilNext, interval, TimeUnit.MILLISECONDS)
	}
	// endregion

	// region Session manager
	fun setupInternalSession() {
		if (!::internalSession.isInitialized) {
			internalSession = getSession("__internal__", true)
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
		} else if (!response.isSuccessful) {
			throw HttpException(response)
		}
	}

	fun getSession(id: String, ignoreLimit: Boolean = false) = sessions.getOrPut(id) {
		if (!ignoreLimit && sessions.size >= BotConfig.get().maxSessions) {
			throw IllegalStateException(L10N.format("error.session.limit"))
		}
		Session(this, id)
	}
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

	val isProd get() = ENV != "dev"// && discord.selfUser.idLong == 563753712376479754L
}