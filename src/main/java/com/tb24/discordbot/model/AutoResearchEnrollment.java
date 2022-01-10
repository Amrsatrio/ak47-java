package com.tb24.discordbot.model;

import java.util.Date;

public class AutoResearchEnrollment {
	public String id;
	public String registrantId;
	public Date nextRun;
	public long rvn = -1;

	public AutoResearchEnrollment() {}

	public AutoResearchEnrollment(String id, String registrantId) {
		this.id = id;
		this.registrantId = registrantId;
	}
}
