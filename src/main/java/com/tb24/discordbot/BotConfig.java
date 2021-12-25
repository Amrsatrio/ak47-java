package com.tb24.discordbot;

import com.tb24.fn.EpicApi;

import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class BotConfig {
	private static final BotConfig INSTANCE;

	static {
		try (FileReader reader = new FileReader("config_" + DiscordBot.ENV + ".json")) {
			INSTANCE = EpicApi.GSON.fromJson(reader, BotConfig.class);
			INSTANCE.applyAssetReaderProperties();
		} catch (IOException e) {
			throw new RuntimeException("Unable to load configuration", e);
		}
	}

	public static BotConfig get() {
		return INSTANCE;
	}

	public String token;
	public String rethinkUrl = "rethinkdb://localhost:28015/ak47";
	public String mongoUrl = "mongodb://localhost:27017";
	public String defaultPrefix = ",";
	public long sessionLifetimeMinutes = 40;
	public int maxSessions = 3000;
	public long homeGuildId;
	public String homeGuildInviteLink;
	public long itemShopChannelId;
	public long premiumChannelId;
	public long mtxAlertsChannelId;
	public long mtxAlertsRoleId;
	public long logsChannelId;
	public List<Long> emojiGuildIds = Collections.emptyList();
	public List<Long> adminUserIds = Collections.emptyList();

	public DeviceAuthQuota deviceAuthQuota = new DeviceAuthQuota();
	public boolean allowUsersToCreateDeviceAuth = true;

	public String proxyHostsFile;
	public String proxyUsername;
	public String proxyPassword;
	public String proxyDomainFormat;

	public EGameFileLoadOption loadGameFiles = EGameFileLoadOption.Local;
	public String gamePath = "C:\\Program Files\\Epic Games\\Fortnite\\FortniteGame\\Content\\Paks";
	public String game; // VersionContainer.game
	public String gameVersion;
	public String encryptionKey;
	public boolean fatalObjectSerializationErrors = true;

	/** We use System.getProperty() in the other module as the config for game files */
	private void applyAssetReaderProperties() {
		if (gamePath != null) System.setProperty("gamePath", gamePath);
		if (game != null) System.setProperty("game", game);
		if (gameVersion != null) System.setProperty("gameVersion", gameVersion);
		if (encryptionKey != null) System.setProperty("encryptionKey", encryptionKey);
		System.setProperty("fatalObjectSerializationErrors", fatalObjectSerializationErrors ? "true" : "false");
	}

	public static class DeviceAuthQuota {
		public int maxForPrivileged = 20;
		public int maxForPremium = 5;
		public int maxForComplimentary = 3;
		public int minAccountAgeInDaysForComplimentary = 90;
		public List<Long> additionalPrivilegedUserIds = Collections.emptyList();
	}

	public enum EGameFileLoadOption {
		NoLoad, Local, Streamed
	}
}
