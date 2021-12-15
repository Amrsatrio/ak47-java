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
	public static final FText InvalidPersonality = new FText("MyTownManager", "InvalidPersonality", "of a matching personality");
	public static final FText SetBonusBuff_Default = new FText("SetBonusBuff", "Default", "an additional bonus");
	public static final FText CosmeticItemDescription_Season = new FText("Fort.Cosmetics", "CosmeticItemDescription_Season", "\nIntroduced in <SeasonText>{0}</>.");
	public static final FText CosmeticItemDescription_SetMembership = new FText("Fort.Cosmetics", "CosmeticItemDescription_SetMembership", "\nPart of the <SetName>{0}</> set.");

	// region Item Categories
	public static final FText HeroAbility_Plural = new FText("EFortItemType", "HeroAbility_Plural", "Abilities");
	public static final FText HeroAbility = new FText("EFortItemType", "HeroAbility", "Ability");
	public static final FText AccountItem = new FText("EFortItemType", "AccountItem", "Account Item");
	public static final FText AccountItem_Plural = new FText("EFortItemType", "AccountItem_Plural", "Account Items");
	public static final FText AccountResource = new FText("EFortItemType", "AccountResource", "Account Resource");
	public static final FText AccountResource_Plural = new FText("EFortItemType", "AccountResource_Plural", "Account Resources");
	public static final FText Alteration = new FText("EFortItemType", "Alteration", "Alteration");
	public static final FText Alteration_Plural = new FText("EFortItemType", "Alteration_Plural", "Alterations");
	public static final FText Ammo = new FText("EFortItemType", "Ammo", "Ammo");
	public static final FText Ammo_Plural = new FText("EFortItemType", "Ammo_Plural", "Ammo");
	public static final FText BackBling = new FText("EFortItemType", "BackBling", "BackBling");
	public static final FText BackPack_Plural = new FText("EFortItemType", "BackPack_Plural", "BackBlings");
	public static final FText BackpackPickup = new FText("EFortItemType", "BackpackPickup", "Backpack");
	public static final FText BackpackPickup_Plural = new FText("EFortItemType", "BackpackPickup_Plural", "Backpacks");
	public static final FText Badge = new FText("EFortItemType", "Badge", "Badge");
	public static final FText Badge_Plural = new FText("EFortItemType", "Badge_Plural", "Badges");
	public static final FText BattleLabDevice = new FText("EFortItemType", "BattleLabDevice", "Battle Lab Device");
	public static final FText BattleLabDeviceAccount = new FText("EFortItemType", "BattleLabDeviceAccount", "Battle Lab Device (Account)");
	public static final FText BattleLabDevice_Plural = new FText("EFortItemType", "BattleLabDevice_Plural", "Battle Lab Devices");
	public static final FText BattleLabDeviceAccount_Plural = new FText("EFortItemType", "BattleLabDeviceAccount_Plural", "Battle Lab Devices (Account)");
	public static final FText Buff = new FText("EFortItemType", "Buff", "Buff");
	public static final FText BuffCredit = new FText("EFortItemType", "BuffCredit", "Buff");
	public static final FText BuffCredit_Plural = new FText("EFortItemType", "BuffCredit_Plural", "Buffs");
	public static final FText Buff_Plural = new FText("EFortItemType", "Buff_Plural", "Buffs");
	public static final FText BuildingPiece = new FText("EFortItemType", "BuildingPiece", "Building Piece");
	public static final FText BuildingPiece_Plural = new FText("EFortItemType", "BuildingPiece_Plural", "Building Pieces");
	public static final FText ItemCache = new FText("EFortItemType", "ItemCache", "Cache");
	public static final FText ItemCache_Plural = new FText("EFortItemType", "ItemCache_Plural", "Caches");
	public static final FText CodeToken = new FText("EFortItemType", "CodeToken", "CodeToken");
	public static final FText CodeToken_Plural = new FText("EFortItemType", "CodeToken_Plural", "CodeTokens");
	public static final FText CollectedResource = new FText("EFortItemType", "CollectedResource", "Collected Resource");
	public static final FText CollectedResource_Plural = new FText("EFortItemType", "CollectedResource_Plural", "Collected Resources");
	public static final FText CollectionBookPage = new FText("EFortItemType", "CollectionBookPage", "Collection Book");
	public static final FText CollectionBookPage_Plural = new FText("EFortItemType", "CollectionBookPage_Plural", "Collection Book Pages");
	public static final FText Consumable = new FText("EFortItemType", "Consumable", "Consumable");
	public static final FText ConsumableAccountItem = new FText("EFortItemType", "ConsumableAccountItem", "Consumable");
	public static final FText ConsumableAccountItem_Plural = new FText("EFortItemType", "ConsumableAccountItem_Plural", "Consumables");
	public static final FText Consumable_Plural = new FText("EFortItemType", "Consumable_Plural", "Consumables");
	public static final FText CharacterPart = new FText("EFortItemType", "CharacterPart", "Cosmetic Item");
	public static final FText CharacterPart_Plural = new FText("EFortItemType", "CharacterPart_Plural", "Cosmetic Items");
	public static final FText CosmeticLocker = new FText("EFortItemType", "CosmeticLocker", "Cosmetic Locker");
	public static final FText CosmeticLocker_Plural = new FText("EFortItemType", "CosmeticLocker_Plural", "Cosmetic Lockers");
	public static final FText WeaponCreativePhone = new FText("EFortItemType", "WeaponCreativePhone", "Creative Phone");
	public static final FText WeaponCreativePhone_Plural = new FText("EFortItemType", "WeaponCreativePhone_Plural", "Creative Phones");
	public static final FText CreativePlayset = new FText("EFortItemType", "CreativePlayset", "Creative Playset");
	public static final FText CreatePlot = new FText("EFortItemType", "CreatePlot", "Creative Plot");
	public static final FText CreativePlot_Plural = new FText("EFortItemType", "CreativePlot_Plural", "Creative Plots");
	public static final FText Currency_Plural = new FText("EFortItemType", "Currency_Plural", "Currencies");
	public static final FText Currency = new FText("EFortItemType", "Currency", "Currency");
	public static final FText DailyRewardScheduleToken = new FText("EFortItemType", "DailyRewardScheduleToken", "Daily Reward");
	public static final FText DailyRewardScheduleToken_Plural = new FText("EFortItemType", "DailyRewardScheduleToken_Plural", "Daily Rewards");
	public static final FText Deco = new FText("EFortItemType", "Deco", "Decorative Item");
	public static final FText Deco_Plural = new FText("EFortItemType", "Deco_Plural", "Decorative Items");
	public static final FText Defender = new FText("EFortItemType", "Defender", "Defender");
	public static final FText Defender_Plural = new FText("EFortItemType", "Defender_Plural", "Defenders");
	public static final FText DeployableBaseCloudSave = new FText("EFortItemType", "DeployableBaseCloudSave", "Deployable Base");
	public static final FText DeployableBaseCloudSave_Plural = new FText("EFortItemType", "DeployableBaseCloudSave_Plural", "Deployable Bases");
	public static final FText EditTool = new FText("EFortItemType", "EditTool", "Editing Tool");
	public static final FText EditTool_Plural = new FText("EFortItemType", "EditTool_Plural", "Editing Tools");
	public static final FText Emote = new FText("EFortItemType", "Emote", "Emote");
	public static final FText Emote_Plural = new FText("EFortItemType", "Emote_Plural", "Emotes");
	public static final FText Expedition = new FText("EFortItemType", "Expedition", "Expedition");
	public static final FText Expedition_Plural = new FText("EFortItemType", "Expedition_Plural", "Expeditions");
	public static final FText Food = new FText("EFortItemType", "Food", "Food");
	public static final FText Food_Plural = new FText("EFortItemType", "Food_Plural", "Food");
	public static final FText Gadget = new FText("EFortItemType", "Gadget", "Gadget");
	public static final FText HomebaseGadget = new FText("EFortItemType", "HomebaseGadget", "Gadget");
	public static final FText Gadget_Plural = new FText("EFortItemType", "Gadget_Plural", "Gadgets");
	public static final FText HomebaseGadget_Plural = new FText("EFortItemType", "HomebaseGadget_Plural", "Gadgets");
	public static final FText GameplayModifier = new FText("EFortItemType", "GameplayModifier", "Gameplay Modifier");
	public static final FText GameplayModifier_Plural = new FText("EFortItemType", "GameplayModifier_Plural", "Gameplay Modifiers");
	public static final FText Glider = new FText("EFortItemType", "Glider", "Glider");
	public static final FText Glider_Plural = new FText("EFortItemType", "Glider_Plural", "Gliders");
	public static final FText PickAxe = new FText("EFortItemType", "PickAxe", "Harvesting Tool");
	public static final FText WeaponHarvest = new FText("EFortItemType", "WeaponHarvest", "Harvesting Tool");
	public static final FText PickAxe_Plural = new FText("EFortItemType", "PickAxe_Plural", "Harvesting Tools");
	public static final FText WeaponHarvest_Plural = new FText("EFortItemType", "WeaponHarvest_Plural", "Harvesting Tools");
	public static final FText Hero = new FText("EFortItemType", "Hero", "Hero");
	public static final FText Hero_Plural = new FText("EFortItemType", "Hero_Plural", "Heroes");
	public static final FText HomebaseBannerIcon = new FText("EFortItemType", "HomebaseBannerIcon", "Homebase Banner");
	public static final FText HomebaseBannerColor = new FText("EFortItemType", "HomebaseBannerColor", "Homebase Banner Color");
	public static final FText HomebaseBannerColor_Plural = new FText("EFortItemType", "HomebaseBannerColor_Plural", "Homebase Banner Colors");
	public static final FText HomebaseBannerIcon_Plural = new FText("EFortItemType", "HomebaseBannerIcon_Plural", "Homebase Banners");
	public static final FText Ingredient = new FText("EFortItemType", "Ingredient", "Ingredient");
	public static final FText Ingredient_Plural = new FText("EFortItemType", "Ingredient_Plural", "Ingredients");
	public static final FText LoadingScreen = new FText("EFortItemType", "LoadingScreen", "Loading Screen");
	public static final FText LoadingScreen_Plural = new FText("EFortItemType", "LoadingScreen_Plural", "Loading Screens");
	public static final FText CampaignHeroLoadout = new FText("EFortItemType", "CampaignHeroLoadout", "Loadout");
	public static final FText CampaignHeroLoadout_Plural = new FText("EFortItemType", "CampaignHeroLoadout_Plural", "Loadouts");
	public static final FText CardPack = new FText("EFortItemType", "CardPack", "Loot");
	public static final FText CardPack_Plural = new FText("EFortItemType", "CardPack_Plural", "Loot");
	public static final FText WeaponMelee = new FText("EFortItemType", "WeaponMelee", "Melee Weapon");
	public static final FText WeaponMelee_Plural = new FText("EFortItemType", "WeaponMelee_Plural", "Melee Weapons");
	public static final FText MissionItem = new FText("EFortItemType", "MissionItem", "Mission Item");
	public static final FText MissionItem_Plural = new FText("EFortItemType", "MissionItem_Plural", "Mission Items");
	public static final FText Music = new FText("EFortItemType", "Music", "Music");
	public static final FText Music_Plural = new FText("EFortItemType", "Music_Plural", "Music");
	public static final FText Skin = new FText("EFortItemType", "Skin", "Outfit");
	public static final FText Skin_Plural = new FText("EFortItemType", "Skin_Plural", "Outfits");
	public static final FText PlayerSurveyToken = new FText("EFortItemType", "PlayerSurveyToken", "Player Survey Token");
	public static final FText PlayerSurveyToken_Plural = new FText("EFortItemType", "PlayerSurveyToken_Plural", "Player Survey Token");
	public static final FText Playset = new FText("EFortItemType", "Playset", "Playset");
	public static final FText PlaysetProp = new FText("EFortItemType", "PlaysetProp", "Playset Prop");
	public static final FText PlaysetProp_Plural = new FText("EFortItemType", "PlaysetProp_Plural", "Playset Props");
	public static final FText Playset_Plural = new FText("EFortItemType", "Playset_Plural", "Playsets");
	public static final FText Profile = new FText("EFortItemType", "Profile", "Profile");
	public static final FText Profile_Plural = new FText("EFortItemType", "Profile_Plural", "Profiles");
	public static final FText Quest = new FText("EFortItemType", "Quest", "Quest");
	public static final FText Quest_Plural = new FText("EFortItemType", "Quest_Plural", "Quests");
	public static final FText Quota = new FText("EFortItemType", "Quota", "Quota");
	public static final FText Quota_Plural = new FText("EFortItemType", "Quota_Plural", "Quotas");
	public static final FText WeaponRanged = new FText("EFortItemType", "WeaponRanged", "Ranged Weapon");
	public static final FText WeaponRanged_Plural = new FText("EFortItemType", "WeaponRanged_Plural", "Ranged Weapons");
	public static final FText Schematic = new FText("EFortItemType", "Schematic", "Schematic");
	public static final FText Schematic_Plural = new FText("EFortItemType", "Schematic_Plural", "Schematics");
	public static final FText HomebaseNode = new FText("EFortItemType", "HomebaseNode", "Skill Tree Node");
	public static final FText HomebaseNode_Plural = new FText("EFortItemType", "HomebaseNode_Plural", "Skill Tree Nodes");
	public static final FText SpecialItem = new FText("EFortItemType", "SpecialItem", "Special Item");
	public static final FText SpecialItem_Plural = new FText("EFortItemType", "SpecialItem_Plural", "Special Items");
	public static final FText Stack = new FText("EFortItemType", "Stack", "Stack");
	public static final FText Stack_Plural = new FText("EFortItemType", "Stack_Plural", "Stacks");
	public static final FText Stat = new FText("EFortItemType", "Stat", "Stat");
	public static final FText Stat_Plural = new FText("EFortItemType", "Stat_Plural", "Stats");
	public static final FText Outpost = new FText("EFortItemType", "Outpost", "Storm Shield");
	public static final FText Outpost_Plural = new FText("EFortItemType", "Outpost_Plural", "Storm Shields");
	public static final FText Worker = new FText("EFortItemType", "Worker", "Survivor");
	public static final FText Worker_Plural = new FText("EFortItemType", "Worker_Plural", "Survivors");
	public static final FText TeamPerk = new FText("EFortItemType", "TeamPerk", "Team Perk");
	public static final FText TeamPerk_Plural = new FText("EFortItemType", "TeamPerk_Plural", "Team Perks");
	public static final FText Token = new FText("EFortItemType", "Token", "Token");
	public static final FText Token_Plural = new FText("EFortItemType", "Token_Plural", "Tokens");
	public static final FText ConversionControl = new FText("EFortItemType", "ConversionControl", "Transform Recipe");
	public static final FText ConversionControl_Plural = new FText("EFortItemType", "ConversionControl_Plural", "Transform Recipes");
	public static final FText Trap = new FText("EFortItemType", "Trap", "Trap");
	public static final FText Trap_Plural = new FText("EFortItemType", "Trap_Plural", "Traps");
	public static final FText CreativeUserPrefab = new FText("EFortItemType", "CreativeUserPrefab", "User Prefab");
	public static final FText CreativePlayset_Plural = new FText("EFortItemType", "CreativePlayset_Plural", "User Prefabs");
	public static final FText CreativeUserPrefab_Plural = new FText("EFortItemType", "CreativeUserPrefab_Plural", "User Prefabs");
	public static final FText Vehicle = new FText("EFortItemType", "Vehicle", "Vehicle");
	public static final FText Vehicle_Plural = new FText("EFortItemType", "Vehicle_Plural", "Vehicles");
	public static final FText Weapon = new FText("EFortItemType", "Weapon", "Weapon");
	public static final FText Weapon_Plural = new FText("EFortItemType", "Weapon_Plural", "Weapons");
	public static final FText WorldResource = new FText("EFortItemType", "WorldResource", "World Resource");
	public static final FText WorldResource_Plural = new FText("EFortItemType", "WorldResource_Plural", "World Resources");
	public static final FText Wrap = new FText("EFortItemType", "Wrap", "Wrap");
	public static final FText Wrap_Plural = new FText("EFortItemType", "Wrap_Plural", "Wraps");
	public static final FText WorldItem = new FText("EFortItemType", "WorldItem", "Zone Inventory Item");
	public static final FText WorldItem_Plural = new FText("EFortItemType", "WorldItem_Plural", "Zone Inventory Items");
	// endregion

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
