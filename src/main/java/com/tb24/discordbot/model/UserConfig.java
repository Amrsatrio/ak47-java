package com.tb24.discordbot.model;

import com.tb24.fn.model.account.Token;

import java.util.HashMap;

public class UserConfig {
	public String id;
	public HashMap<String, Token> sessions = new HashMap<>();
	public String loggedInAccountId;
	public GiftConfig gift;
	public LockerImageConfig lockerImage;
	public transient boolean persisted;

	public UserConfig() {
		persisted = true;
	}

	public UserConfig(String id) {
		this.id = id;
	}

	public static class GiftConfig {
		public String wrap;
		public String message;
	}

	public static class LockerImageConfig {
		public String format = "png";
	}
}
