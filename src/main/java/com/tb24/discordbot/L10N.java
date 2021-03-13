package com.tb24.discordbot;

import com.google.common.reflect.TypeToken;
import com.google.gson.JsonSyntaxException;
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

import static com.tb24.fn.EpicApi.GSON;

public class L10N {
	public static final FText TOTAL_BUNDLE_ITEMS = new FText("", "3FA85FF74000638C17CD57B45CF60F20", "{total bundle items} ITEM BUNDLE");
	public static final FText GiftSuccessDesc = new FText("FortGiftingScreen", "GiftSuccessDesc", "All gifts were delivered successfully.");
	public static final FText AlreadyOwned = new FText("FortGiftingScreen", "AlreadyOwned", "Already owned");
	public static final FText BannerIcon = new FText("FortGiftingScreen", "BannerIcon", "BANNER ICON");
	public static final FText GiftDeclined = new FText("FortGiftingScreen", "GiftDeclined", "Gift Declined");
	public static final FText GiftFailedTwoAccounts = new FText("FortGiftingScreen", "GiftFailedTwoAccounts", "Multiple Errors Occurred:\n  - {0}\n  - {1}");
	public static final FText GiftFailedThreeAccounts = new FText("FortGiftingScreen", "GiftFailedThreeAccounts", "Multiple Errors Occurred:\n  - {0}\n  - {1}\n  - {2}");
	public static final FText GiftFailedFourAccounts = new FText("FortGiftingScreen", "GiftFailedFourAccounts", "Multiple Errors Occurred:\n  - {0}\n  - {1}\n  - {2}\n  - {3}");
	public static final FText GiftFailedFivePlusAccounts = new FText("FortGiftingScreen", "GiftFailedFivePlusAccounts", "Multiple Errors Occurred:\n  - {0}\n  - {1}\n  - {2}\n  - {3}\n  ...");
	public static final FText NotFortnitePlayer = new FText("FortGiftingScreen", "NotFortnitePlayer", "Not a Fortnite Player");
	public static final FText ConnectionError = new FText("FortGiftingScreen", "ConnectionError", "Request Error");
	public static final FText GiftSuccessTitle = new FText("FortGiftingScreen", "GiftSuccessTitle", "Success!");
	public static final FText ConnectionErrorDesc = new FText("FortGiftingScreen", "ConnectionErrorDesc", "There was an error processing your gift request. Please try again later.\n\n{0}");
	public static final FText NotPlayerText = new FText("FortGiftingScreen", "NotPlayerText", "This Epic friend does not have a Fortnite account. A Fortnite account is required to receive gifts in Fortnite.");
	public static final FText AlreadyOwnedText = new FText("FortGiftingScreen", "AlreadyOwnedText", "This friend already owns this item or is otherwise ineligible. Please select another friend to gift to.");
	public static final FText OwnedText = new FText("FortGiftingScreen", "OwnedText", "This friend has opted-out of being able to be sent gifts.");
	public static final FText GiftFailedAlreadyOwned = new FText("FortGiftingScreen", "GiftFailedAlreadyOwned", "{0} already owns this item and cannot be sent another one as a gift.");
	public static final FText GiftFailedCrossPlatform = new FText("FortGiftingScreen", "GiftFailedCrossPlatform", "{0} cannot be sent gifts unless they are on the same platform as you.");
	public static final FText DailyGiftsRemaining = new FText("FortGiftingScreen", "DailyGiftsRemaining", "{0} daily gifts remaining");
	public static final FText GiftFailedFriendRequirement = new FText("FortGiftingScreen", "GiftFailedFriendRequirement", "{0} has not been your friend for long enough to be sent gifts. Please try again later.");
	public static final FText GiftFailedOptOut = new FText("FortGiftingScreen", "GiftFailedOptOut", "{0} has opted-out of being able to be sent gifts.");
	public static final FText FriendsSelected = new FText("FortGiftingScreen", "FriendsSelected", "{0} of {1} Selected");
	public static final FText GiftFailedBPAlreadyOwned = new FText("FortGiftingScreen", "GiftFailedBPAlreadyOwned", "{0} purchased the Battle Pass for themselves during this gifting process.\n You will now be granted a Battle Pass Gift Token that you can use to gift a Battle Pass to another friend.");
	public static final FText GiftFailedGeneric = new FText("FortGiftingScreen", "GiftFailedGeneric", "{0} was not able to be sent this gift.");
	public static final FText PercentRepresentation = new FText("FortGiftingScreen", "PercentRepresentation", "{0}% {1}");
	public static final FText MessageCount = new FText("FortGiftingScreen", "MessageCount", "{0}/{1}");
	public static final FText QuantityRepresentation = new FText("FortGiftingScreen", "QuantityRepresentation", "{0}x {1}");
	public static final FText DaysRemainingText = new FText("FortGiftingUserItem", "DaysRemainingText", "This friend can receive gifts in {0} more days!");
	public static final FText Treat = new FText("FortGiftingUserItem", "Treat", "Treat Yo Self!");
	public static final FText Already = new FText("FortGiftingUserItem", "Already", "You already own this item");
	public static final FText MESSAGE_BOX_HINT = new FText("", "4143C990448BE6E2F58347923C1CED11", "Enter a message for the gift's recipient!");
	public static final FText MESSAGE_BOX_DEFAULT_MSG = new FText("", "C4562D2A4C4F7939FBA8A8B2311A257E", "Hope you like this gift. Good luck; have fun!");
	public static final FText CANT_BE_REFUNDED = new FText("", "2CD2F0D84F6F6F3518D241989F8A2614", "*Note: Gifts cannot be refunded");
	public static final FText Manager = new FText("FortWorkerType", "Manager", "Lead Survivor");
	public static final FText E_FILTER_ALL = new FText("", "C3AE82E3441E72074BCD7F87BD9EC1E2", "All Expeditions");
	public static final FText E_FILTER_ONGOING = new FText("", "F6EC46E74C83A1A70F04FCA58E2C66CE", "In Progress");
	public static final FText E_FILTER_LAND = new FText("", "0997D44F445155FBA9A2768896ACD63E", "Land");
	public static final FText E_FILTER_SEA = new FText("", "0CC46FF344057CE022CA0A945330EC78", "Sea");
	public static final FText E_FILTER_AIR = new FText("", "389A607844AA28B7972408AC66FD23AC", "Air");
	public static final FText TARGET_SQUAD_POWER = new FText("", "1BBEBD344847B8384FFAB1BBE07CF114", "Target Squad Power:");
	public static final FText EXPEDITION_DURATION = new FText("", "08F2AE94413785B08D3EC5ACC9E25E41", "Expedition Duration: {A}");
	public static final FText EXPIRES = new FText("", "7D4C54AF4A7AAC19FFECE3A724C535DA", "Expires: {A}");
	public static final FText RETURNS = new FText("", "221A64D642AFD10C70D84ABABFDFEF2F", "Returns: {A}");
	private static final Map<String, String> locData;

	static {
		Map<String, String> map;
		try {
			map = GSON.fromJson(new FileReader("src/L10N.json"), new TypeToken<Map<String, String>>() {
			}.getType());
		} catch (FileNotFoundException | JsonSyntaxException e) {
			map = new HashMap<>();
		}
		locData = map;
	}

	public static String format(String key) {
		return locData.getOrDefault(key, key);
	}

	public static String format(String key, Object... args) {
		String entry = locData.get(key);
		return entry != null ? MessageFormat.format(entry, args) : key;
	}
}
