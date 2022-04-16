package com.tb24.discordbot.item;

import me.fungames.jfortniteparse.fort.enums.EFortItemTier;
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition;
import me.fungames.jfortniteparse.fort.exports.FortWeaponItemDefinition;
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText;

import java.util.Locale;

public class ItemUtils {
	private ItemUtils() {
	}

	public static int getItemTierInt(EFortItemTier tierEnum) {
		return getItemTierInt(tierEnum, true);
	}

	public static int getItemTierInt(EFortItemTier tierEnum, boolean cap) {
		if (tierEnum == null) {
			return 0;
		}

		int i = tierEnum.ordinal();
		return cap ? Math.min(i, 5) : i;
	}

	public static int getTierFromTidSuffix(String templateId) {
		templateId = templateId.toLowerCase(Locale.ENGLISH);

		if (templateId.endsWith("t01")) {
			return 1;
		} else if (templateId.endsWith("t02")) {
			return 2;
		} else if (templateId.endsWith("t03")) {
			return 3;
		} else if (templateId.endsWith("t04")) {
			return 4;
		} else if (templateId.endsWith("t05")) {
			return 5;
		} else if (templateId.endsWith("t06")) {
			return 6;
		}

		return 0;
	}

	public static int getTier(FortItemDefinition transformedDef) {
		int tier = ItemUtils.getItemTierInt(transformedDef.Tier);
		int maxTier = ItemUtils.getItemTierInt(transformedDef.MaxTier);
		return maxTier == 0 ? tier : maxTier;
	}

	public static FText getDisplayTierFmtString(FortWeaponItemDefinition.EFortDisplayTier displayTier) {
		switch (displayTier) {
			case Handmade: // T00
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Handmade", "Handmade");
			case Copper: // T01
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Copper", "Copper");
			case Silver: // T02
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Silver", "Silver");
			case Malachite: // T03
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Malachite", "Malachite");
			case Obsidian: // T04
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Obsidian", "Obsidian");
			case Shadowshard: // T04 Crystal
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Shadowshard", "Shadowshard");
			case Brightcore: // T05
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Brightcore", "Brightcore");
			case Sunbeam: // T05 Crystal
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Sunbeam", "Sunbeam");
			case Spectrolite: // T06
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Spectrolite", "Spectrolite");
			case Moonglow: // T06 Crystal
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Moonglow", "Moonglow");
			default:
				return new FText("Fort.Weapon.Defaults", "EFortDisplayTier.Invalid", "Invalid");
		}
	}
}
