package com.tb24.discordbot;

import com.google.common.collect.ImmutableMap;
import com.rethinkdb.net.Connection;
import com.tb24.discordbot.commands.CommandManager;
import com.tb24.discordbot.commands.GrantType;
import com.tb24.discordbot.util.Utils;
import com.tb24.fn.model.account.DeviceAuth;
import com.tb24.fn.util.EAuthClient;
import com.tb24.uasset.AssetManager;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.TextChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.login.LoginException;

import kotlin.collections.MapsKt;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;

import static com.rethinkdb.RethinkDB.r;

public final class DiscordBot {
	public static final String VERSION = "6.0.3";
	private static final Logger LOGGER = LoggerFactory.getLogger("DiscordBot");
	public static final CertificatePinner CERT_PINNER = new CertificatePinner.Builder()
		.add("discordapp.com", "sha256/DACsWb3zfNT9ttV6g6o5wwpzvgKJ66CliW2GCh2m8LQ=")
		.add("discordapp.com", "sha256/x9SZw6TwIqfmvrLZ/kz1o0Ossjmn728BnBKpUFqGNVM=")
		.add("discordapp.com", "sha256/58qRu/uxh4gFezqAcERupSkRYBlBAvfcw7mEjGPLnNU=")
		.build();
	public static final boolean LOAD_PAKS = System.getProperty("loadPaks", "false").equals("true");
	public static final String ENV = System.getProperty("env", "dev");
	public static DiscordBot instance;
	public OkHttpClient okHttpClient;
	public JDA discord;
	public Connection dbConn;
	public SavedLoginsManager savedLoginsManager;
	public Map<Long, PrefixConfig> prefixMap = new HashMap<>();
	public Map<String, Session> sessions = new HashMap<>();
	public Session internalSession;
	public CommandManager commandManager;
	public CatalogManager catalogManager;

	public static void main(String[] args) {
		if (args.length < 1 || Utils.isEmpty(args[0])) {
			LOGGER.warn("No token provided");
			System.exit(1);
			return;
		}
		LOGGER.info("Starting AK-47 Discord Bot...");
		if (LOAD_PAKS) AssetManager.INSTANCE.loadPaks();
		try {
			instance = new DiscordBot(args[0]);
		} catch (LoginException | InterruptedException e) {
			LOGGER.error("Initialization failure", e);
			System.exit(1);
		}
	}

	public DiscordBot(String token) throws LoginException, InterruptedException {
		String dbUrl = "rethinkdb://localhost:28015/ak47";
		LOGGER.info("Connecting to database {}...", dbUrl);
		dbConn = r.connection(dbUrl).connect();
		savedLoginsManager = new SavedLoginsManager(dbConn);
		okHttpClient = new OkHttpClient.Builder()
			.certificatePinner(CERT_PINNER)
			.build();
		setupInternalSession();
		catalogManager = new CatalogManager(this);
		LOGGER.info("Connecting to Discord...");
		discord = JDABuilder.createDefault(token)
			.setHttpClient(okHttpClient)
			.build();
		discord.addEventListener(commandManager = new CommandManager(this));
//		discord.addEventListener(new ReactionHandler(this)); // TODO doesn't respond if the channel hasn't been interacted with
		discord.addEventListener(new GhostPingHandler(this));
		discord.awaitReady();
		LOGGER.info("Logged in as {}! v{}", discord.getSelfUser().getAsTag(), VERSION);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			internalSession.logout(null);
			discord.shutdown();
		}));
		discord.getPresence().setActivity(Activity.playing("☕ Kotlin/JVM \u00b7 v" + VERSION));
	}

	private void setupInternalSession() {
		internalSession = getSession("__internal__");
		DeviceAuth internalDeviceData = savedLoginsManager.getAll("__internal__").get(0);
		try {
			internalSession.login(null, GrantType.device_auth, ImmutableMap.of(
				"account_id", internalDeviceData.accountId,
				"device_id", internalDeviceData.deviceId,
				"secret", internalDeviceData.secret), EAuthClient.FORTNITE_IOS_GAME_CLIENT, false);
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
		if (!message.isFromGuild()) {
			return getDefaultPrefix();
		}
		PrefixConfig dbEntry = MapsKt.getOrPut(prefixMap, message.getGuild().getIdLong(), () -> r.table("prefix").get(message.getGuild().getIdLong()).run(dbConn, PrefixConfig.class).first());
		if (dbEntry != null) {
			return dbEntry.prefix;
		} else {
			return getDefaultPrefix();
		}
	}

	private String getDefaultPrefix() {
		return ENV.equals("prod") ? "+" : ENV.equals("stage") ? "." : ",";
	}

	public void dlog(String content, MessageEmbed embed) {
		TextChannel tc = discord.getTextChannelById(708832031848661072L);
		if (tc != null) {
			tc.sendMessage(new MessageBuilder(content).setEmbed(embed).build()).queue();
		}
	}

	public static class PrefixConfig {
		public String id;
		public String prefix;
	}
}
