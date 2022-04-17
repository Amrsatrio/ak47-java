package com.tb24.discordbot.model;

public class WakeEntry {
	public String id;
	public String registrantId;
	public String ipPort;
	public String mac;
	public String name;

	public WakeEntry() {}

	public WakeEntry(String registrantId, String ipPort, String mac, String name) {
		this.registrantId = registrantId;
		this.ipPort = ipPort;
		this.mac = mac;
		this.name = name;
	}
}
