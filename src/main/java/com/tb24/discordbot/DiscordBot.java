package com.tb24.discordbot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.base.Throwables;
import com.rethinkdb.net.Connection;
import com.rethinkdb.utils.Internals;
import com.tb24.discordbot.commands.CommandManager;
import com.tb24.discordbot.commands.OnlyChannelCommandSource;
import com.tb24.discordbot.commands.ShopCommandsKt;
import com.tb24.discordbot.managers.CatalogManager;
import com.tb24.discordbot.managers.SavedLoginsManager;
import com.tb24.discordbot.tasks.AutoLoginRewardTask;
import com.tb24.discordbot.tasks.KeychainTask;
import com.tb24.discordbot.util.Utils;
import com.tb24.fn.model.account.DeviceAuth;
import com.tb24.fn.model.assetdata.ESubGame;
import com.tb24.uasset.AssetManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.rethinkdb.RethinkDB.r;

public final class DiscordBot {
	public static final String VERSION = "6.3.9";
	public static final Logger LOGGER = LoggerFactory.getLogger("DiscordBot");
	public static final boolean LOAD_PAKS = System.getProperty("loadPaks", "false").equals("true");
	public static final String ENV = System.getProperty("env", "dev");
	public static final long ITEM_SHOP_CHANNEL_ID = 702307657989619744L;
	public static DiscordBot instance;
	public OkHttpClient okHttpClient;
	public JDA discord;
	public Connection dbConn;
	public SavedLoginsManager savedLoginsManager;
	public Map<Long, PrefixConfig> prefixMap = new HashMap<>();
	public Map<String, Session> sessions = ExpiringMap.builder()
		.expiration(40, TimeUnit.MINUTES)
		.expirationPolicy(ExpirationPolicy.ACCESSED)
		.build();
	public Session internalSession;
	public CommandManager commandManager;
	public CatalogManager catalogManager;
	public AutoLoginRewardTask autoLoginRewardTask = new AutoLoginRewardTask(this);
	public KeychainTask keychainTask = new KeychainTask(this);
	private final ScheduledThreadPoolExecutor scheduledExecutor = new ScheduledThreadPoolExecutor(2);

	public static void main(String[] args) {
		if (args.length < 1 || Utils.isEmpty(args[0])) {
			LOGGER.warn("No token provided");
			System.exit(1);
			return;
		}
		LOGGER.info("Starting Discord Bot...");
		if (LOAD_PAKS) AssetManager.INSTANCE.loadPaks(false, 0);
		try {
			instance = new DiscordBot(args[0]);
		} catch (Throwable e) {
			LOGGER.error("Initialization failure", e);
			System.exit(1);
		}
	}

	public DiscordBot(String token) throws LoginException, InterruptedException {
		String dbUrl = "rethinkdb://localhost:28015/ak47";
		LOGGER.info("Connecting to database {}...", dbUrl);
		Internals.getInternalMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
		dbConn = r.connection(dbUrl).connect();
		savedLoginsManager = new SavedLoginsManager(dbConn);
		/*String port = System.getProperty("apiPort");
		if (port != null) {
			ApiServerKt.main(new String[]{"", port});
		}*/
		okHttpClient = new OkHttpClient();
		setupInternalSession();
		if (LOAD_PAKS) keychainTask.run();
		catalogManager = new CatalogManager();
		LOGGER.info("Connecting to Discord...");
		JDABuilder builder = JDABuilder.createDefault(token).setHttpClient(okHttpClient);
		if (ENV.equals("prod") || ENV.equals("stage")) {
			builder.enableIntents(GatewayIntent.GUILD_MEMBERS);
		}
		discord = builder.build();
		discord.addEventListener(commandManager = new CommandManager(this));
//		discord.addEventListener(new ReactionHandler(this)); // TODO doesn't respond if the channel hasn't been interacted with
		discord.addEventListener(new GuildListeners(this));
		discord.awaitReady();
		LOGGER.info("Logged in as {}! v{}", discord.getSelfUser().getAsTag(), VERSION);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			internalSession.logout(null);
			discord.shutdown();
		}));
		discord.getPresence().setActivity(Activity.playing(".help \u00b7 v" + VERSION));
		if (!ENV.equals("dev")) {
			scheduleUtcMidnightTask();
			if (LOAD_PAKS) scheduleKeychainTask();
		}
	}

	private void scheduleUtcMidnightTask() {
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		ZonedDateTime nextRun = now.withHour(0).withMinute(0).withSecond(30);
		if (now.compareTo(nextRun) > 0) {
			nextRun = nextRun.plusDays(1);
		}
		Runnable task = () -> {
			try {
				setupInternalSession();
				TextChannel itemShopChannel = discord.getTextChannelById(ITEM_SHOP_CHANNEL_ID);
				if (itemShopChannel != null) {
					ShopCommandsKt.executeShopText(new OnlyChannelCommandSource(this, itemShopChannel), ESubGame.Athena);
					ShopCommandsKt.executeShopImage(new OnlyChannelCommandSource(this, itemShopChannel));
				}
			} catch (Throwable e) {
				dlog("__**Failed to auto post item shop**__\n```\n" + Throwables.getStackTraceAsString(e) + "```", null);
				return;
			}
			try {
				autoLoginRewardTask.run();
			} catch (Throwable e) {
				dlog("__**AutoLoginRewardTask failure**__\n```\n" + Throwables.getStackTraceAsString(e) + "```", null);
				AutoLoginRewardTask.TASK_IS_RUNNING.set(false);
			}
		};
		scheduledExecutor.scheduleAtFixedRate(task, Duration.between(now, nextRun).getSeconds(), TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
	}

	private void scheduleKeychainTask() {
		long interval = 15L * 60L * 1000L;
		long timeUntilNext = interval - (System.currentTimeMillis() % interval);
		scheduledExecutor.scheduleAtFixedRate(() -> {
			try {
				keychainTask.run();
			} catch (Throwable e) {
				dlog("__**Keychain task failure**__\n```\n" + Throwables.getStackTraceAsString(e) + "```", null);
				AutoLoginRewardTask.TASK_IS_RUNNING.set(false);
			}
		}, timeUntilNext, interval, TimeUnit.MILLISECONDS);
	}

	public void setupInternalSession() {
		if (internalSession == null) {
			internalSession = getSession("__internal__");
		}
		DeviceAuth internalDeviceData = savedLoginsManager.getAll("__internal__").get(0);
		try {
			internalSession.login(null, internalDeviceData.generateAuthFields(), internalDeviceData.getAuthClient(), false);
			LOGGER.info("Logged in to internal account: {} {}", internalSession.getApi().currentLoggedIn.getDisplayName(), internalSession.getApi().currentLoggedIn.getId());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public Session getSession(String id) {
		Session session = sessions.get(id);
		if (session == null) {
			sessions.put(id, session = new Session(this, id));
		}
		return session;
	}

	public String getCommandPrefix(Message message) {
		if (!message.isFromGuild() || ENV.equals("dev")) {
			return getDefaultPrefix();
		}
		long guildId = message.getGuild().getIdLong();
		PrefixConfig dbEntry = prefixMap.get(guildId);
		if (dbEntry == null) {
			String guildIdString = Long.toUnsignedString(guildId);
			dbEntry = r.table("prefix").get(guildIdString).run(dbConn, PrefixConfig.class).first();
			if (dbEntry == null) {
				dbEntry = new PrefixConfig();
				dbEntry.server = guildIdString;
				dbEntry.prefix = getDefaultPrefix();
			}
			prefixMap.put(guildId, dbEntry);
		}
		return dbEntry.prefix;
	}

	public String getDefaultPrefix() {
		return ENV.equals("prod") ? "+" : ENV.equals("stage") ? "." : ",";
	}

	public void dlog(String content, MessageEmbed embed) {
		TextChannel tc = discord.getTextChannelById(708832031848661072L);
		if (tc != null) {
			tc.sendMessage(new MessageBuilder(content).setEmbed(embed).build()).queue();
		}
	}

	public static class PrefixConfig {
		public String server;
		public String prefix;
	}
}
