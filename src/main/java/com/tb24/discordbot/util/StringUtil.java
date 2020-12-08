package com.tb24.discordbot.util;

import com.google.common.base.Joiner;

import java.util.ArrayList;
import java.util.List;

// from: https://android.googlesource.com/platform/frameworks/base/+/master/packages/SettingsLib/src/com/android/settingslib/utils/StringUtil.java
public class StringUtil {
	public static final int SECONDS_PER_MINUTE = 60;
	public static final int SECONDS_PER_HOUR = 60 * 60;
	public static final int SECONDS_PER_DAY = 24 * 60 * 60;
	public static final int SECONDS_PER_WEEK = 7 * 24 * 60 * 60;
	private static final Joiner SPACE_JOINER = Joiner.on(' ');

	/**
	 * Returns elapsed time for the given millis, in the following format:
	 * 2d 5h 40m 29s
	 *
	 * @param millis      the elapsed time in milli seconds
	 * @param withSeconds include seconds?
	 * @return the formatted elapsed time
	 */
	public static CharSequence formatElapsedTime(long millis, boolean withSeconds) {
		int seconds = (int) (millis / 1000L);

		/*if (!withSeconds) {
			// Round up.
			seconds += 30;
		}*/

		int weeks = 0, days = 0, hours = 0, minutes = 0;

		if (seconds >= SECONDS_PER_WEEK) {
			weeks = seconds / SECONDS_PER_WEEK;
			seconds -= weeks * SECONDS_PER_WEEK;
		}

		if (seconds >= SECONDS_PER_DAY) {
			days = seconds / SECONDS_PER_DAY;
			seconds -= days * SECONDS_PER_DAY;
		}

		if (seconds >= SECONDS_PER_HOUR) {
			hours = seconds / SECONDS_PER_HOUR;
			seconds -= hours * SECONDS_PER_HOUR;
		}

		if (seconds >= SECONDS_PER_MINUTE) {
			minutes = seconds / SECONDS_PER_MINUTE;
			seconds -= minutes * SECONDS_PER_MINUTE;
		}

		List<String> list = new ArrayList<>(4);

		if (weeks > 0) {
			list.add(weeks + "w");
		}

		if (days > 0) {
			list.add(days + "d");
		}

		if (hours > 0) {
			list.add(hours + "h");
		}

		if (minutes > 0) {
			list.add(minutes + "m");
		}

		if (withSeconds && seconds > 0) {
			list.add(seconds + "s");
		}

		if (list.size() == 0) {
			list.add(0 + (withSeconds ? "s" : "m"));
		}

		return SPACE_JOINER.join(list);
	}
}
