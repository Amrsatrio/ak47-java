package com.tb24.discordbot.model;

import java.util.Date;

public class AutoResearchEnrollment {
	public String id;
	public String registrantId;
	public boolean newSystem;
	public Date nextRun;
	public long rvn = -1;
	public transient boolean runSuccessful;

	public AutoResearchEnrollment() {}

	public AutoResearchEnrollment(String id, String registrantId, boolean newSystem) {
		this.id = id;
		this.registrantId = registrantId;
		this.newSystem = newSystem;
	}
}
