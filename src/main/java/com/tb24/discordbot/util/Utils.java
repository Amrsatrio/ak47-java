package com.tb24.discordbot.util;

import com.google.gson.JsonElement;
import com.tb24.fn.model.FortItemStack;
import com.tb24.fn.util.EAuthClient;
import me.fungames.jfortniteparse.fort.objects.FortMcpQuestObjectiveInfo;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import static com.tb24.fn.util.Utils.isNone;

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
		return isNone(objectPath) ? null : "https://benbotfn.tk/api/v1/exportAsset?path=" + objectPath;
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

	public static String progress(int current, int max, int width) {
		int barWidth = width - 2;
		float ratio = (float) current / (float) max;
		int barEnd = (int) (ratio * barWidth + 0.5F);

		StringBuilder sb = new StringBuilder(width);
		sb.append('[');

		for (int i = 0; i < barWidth; ++i) {
			sb.append(i >= barEnd ? ' ' : i == barEnd - 1 ? '>' : '=');
		}

		sb.append(']');
		return sb.toString();
	}

	/**
	 * Computes the Damerau-Levenshtein Distance between two strings. Adapted
	 * from the algorithm at <a href="http://en.wikipedia.org/wiki/Damerau–Levenshtein_distance">Wikipedia: Damerau–Levenshtein distance</a>
	 *
	 * @param s1 The first string being compared.
	 * @param s2 The second string being compared.
	 * @return The number of substitutions, deletions, insertions, and
	 * transpositions required to get from s1 to s2.
	 */
	public static int damerauLevenshteinDistance(String s1, String s2) {
		if (s1 == null && s2 == null) {
			return 0;
		}
		if (s1 != null && s2 == null) {
			return s1.length();
		}
		if (s1 == null && s2 != null) {
			return s2.length();
		}

		int s1Len = s1.length();
		int s2Len = s2.length();
		int[][] H = new int[s1Len + 2][s2Len + 2];

		int INF = s1Len + s2Len;
		H[0][0] = INF;
		for (int i = 0; i <= s1Len; i++) {
			H[i + 1][1] = i;
			H[i + 1][0] = INF;
		}
		for (int j = 0; j <= s2Len; j++) {
			H[1][j + 1] = j;
			H[0][j + 1] = INF;
		}

		Map<Character, Integer> sd = new HashMap<>();
		for (char Letter : (s1 + s2).toCharArray()) {
			if (!sd.containsKey(Letter)) {
				sd.put(Letter, 0);
			}
		}

		for (int i = 1; i <= s1Len; i++) {
			int DB = 0;
			for (int j = 1; j <= s2Len; j++) {
				int i1 = sd.get(s2.charAt(j - 1));
				int j1 = DB;

				if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
					H[i + 1][j + 1] = H[i][j];
					DB = j;
				} else {
					H[i + 1][j + 1] = Math.min(H[i][j], Math.min(H[i + 1][j], H[i][j + 1])) + 1;
				}

				H[i + 1][j + 1] = Math.min(H[i + 1][j + 1], H[i1][j1] + (i - i1 - 1) + 1 + (j - j1 - 1));
			}
			sd.put(s1.charAt(i - 1), i);
		}

		return H[s1Len + 1][s2Len + 1];
	}
}
