package com.tb24.discordbot.webcampaign;

import com.google.gson.JsonObject;

public class IncomingMessage {
	public String connectionId;
	public String type;
	public String commandId;
	public UpdatePayload payload;
	public JsonObject properties;
	public long serverTime;

	public static class UpdatePayload {
		public StateUpdate[] states;
	}

	public static class StateUpdate {
		public String key;
		public String type;
		public int version;
		public JsonObject state;
	}
}
