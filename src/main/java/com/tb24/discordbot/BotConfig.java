package com.tb24.discordbot;

import com.tb24.fn.EpicApi;

import java.io.FileReader;
import java.io.IOException;

public class BotConfig {
	public static final BotConfig INSTANCE;

	static {
		try (FileReader reader = new FileReader("config_" + DiscordBot.ENV + ".json")) {
			INSTANCE = EpicApi.GSON.fromJson(reader, BotConfig.class);
		} catch (IOException e) {
			throw new RuntimeException("Unable to load configuration", e);
		}
	}

	public String token;
	public String defaultPrefix;
	public long homeGuild;
	public String homeGuildInviteLink;
	public long itemShopChannel;
	public long logsChannel;
	public long[] emojiGuilds;

	public boolean loadPaks;
	public String gameContentPath;
	public String gameVersionOverride;
	public String encryptionKeyOverride;
	public boolean fatalObjectSerializationErrors;
}
