package com.tb24.discordbot.util;

import com.google.gson.JsonElement;
import com.tb24.fn.model.FortItemStack;
import com.tb24.fn.util.EAuthClient;

import java.net.URLEncoder;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import me.fungames.jfortniteparse.fort.objects.FortMcpQuestObjectiveInfo;

public class Utils {
	public static final String LOADING_EMOJI = "<a:loading:740564597643149404>";
	public static final long MTX_EMOJI_ID = 751101530626588713L;
	public static final String MTX_EMOJI = "<:vbucks:" + MTX_EMOJI_ID + ">";
	private static final String[] ERROR_TITLES = new String[]{
		"DRAT! WE'RE SORRY",
		"NOT THE LLAMA YOU'RE LOOKING FOR",
		"THAT WASN\'T SUPPOSED TO HAPPEN",
		"THERE WAS AN ERROR",
		"UH OH!  SOMETHING GOOFED",
		"WE HIT A ROADBLOCK",
		"WHOOPS!"
	};
	private static final Random RANDOM = new Random();
	public static final String HOMEBASE_GUILD_INVITE = "https://discord.gg/HCVjeXJ";
	public static final int[] eneryTable = {
		0, 5, 8, 27, 58, 75, 93, 107, 120, 131, 142, 167, 193,
		219, 245, 311, 378, 429, 480, 583, 685, 748, 810, 904,
		998, 1066, 1134, 1202, 1225, 1248, 1272, 1314, 1357,
		1399, 1423, 1447, 1470, 1570, 1670, 1770, 1930, 2089,
		2248, 2320, 2391, 2463, 2539, 2615, 2692, 2790, 2889,
		2988, 3043, 3099, 3154, 3247, 3339, 3432, 3522, 3613,
		3703, 3811, 3920, 4028, 4098, 4168, 4238, 4357, 4475,
		4593, 4704, 4816, 4927, 5022, 5117, 5212, 5307, 5402,
		5497, 5609, 5721, 5833, 5992, 6151, 6310, 6470, 6633,
		6788, 6973, 7154, 7331, 7484, 7633, 7782, 7920, 8058,
		8196, 8431, 8667, 8902, 9078, 9253, 9428, 9597, 9669,
		9728, 9802, 9868, 9971, 10082, 10189, 10296, 10411, 10503,
		10678, 10702, 10925, 11000, 11140, 11266, 11466, 11649,
		11864, 12046, 12176, 12320, 12465, 12606, 12724, 12838, // 12838.5
		12959, 13055, 13248, 13419, 13589, 13760, 13897, 14035,
		14173, 14315
	};
	public static final Pattern EMAIL_ADDRESS
		= Pattern.compile(
		"[a-zA-Z0-9\\+\\.\\_\\%\\-\\+]{1,256}" +
			"\\@" +
			"[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" +
			"(" +
			"\\." +
			"[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25}" +
			")+"
	);

	public static String redirect(EAuthClient client) {
		return String.format("https://www.epicgames.com/id/api/redirect?clientId=%s&responseType=code", client.clientId);
	}

	public static String login(String redirectUrl) {
		if (redirectUrl != null) {
			return "https://www.epicgames.com/id/login?redirectUrl=" + URLEncoder.encode(redirectUrl);
		} else {
			return "https://www.epicgames.com/id/login";
		}
	}

	public static String randomError() {
		return ERROR_TITLES[RANDOM.nextInt(ERROR_TITLES.length)];
	}

	public static boolean isEmpty(CharSequence cs) {
		return cs == null || cs.length() == 0;
	}

	public static String emoji(int codePoint) {
		return new String(Character.toChars(codePoint));
	}

	public static String loadingText(String s) {
		return s.contains("%LOADING%") ? s.replace("%LOADING%", LOADING_EMOJI) : (s + " " + LOADING_EMOJI);
	}

	public static String benBotExportAsset(String objectPath) {
		return null; //com.tb24.fn.util.Utils.isNone(objectPath) ? null : "https://benbotfn.tk/api/v1/exportAsset?path=" + objectPath;
	}

	public static int getCompletion(FortMcpQuestObjectiveInfo objective, FortItemStack item) {
		if (item == null || item.attributes == null) {
			return -1;
		}

		String backendName = "completion_" + objective.BackendName.toString().toLowerCase(Locale.ENGLISH);

		for (Map.Entry<String, JsonElement> entry : item.attributes.entrySet()) {
			if (entry.getKey().startsWith(backendName)) {
				return entry.getValue().isJsonPrimitive() ? Math.min(entry.getValue().getAsInt(), objective.Count) : -1;
			}
		}

		return -1;
	}

	public static int mod(int a, int b) {
		int r = a % b;
		return r < 0 ? r + b : r;
	}
}
