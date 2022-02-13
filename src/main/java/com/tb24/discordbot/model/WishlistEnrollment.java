package com.tb24.discordbot.model;

public class WishlistEnrollment {
	public String id;
	public String registrantId;
	public boolean autoBuy;

	public WishlistEnrollment() {}

	public WishlistEnrollment(String id, String registrantId) {
		this.id = id;
		this.registrantId = registrantId;
	}
}
