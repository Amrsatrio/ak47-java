@file:JvmName("ItemUtils")

package com.tb24.discordbot.item

import me.fungames.jfortniteparse.fort.enums.EFortItemTier
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition
import me.fungames.jfortniteparse.fort.exports.FortWeaponItemDefinition.EFortDisplayTier
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText

fun getItemTierInt(tierEnum: EFortItemTier?, cap: Boolean = true): Int {
	val i = tierEnum?.ordinal ?: 0
	return if (cap) i.coerceAtMost(5) else i
}

val FortItemDefinition.cappedTier: Int get() {
	val tier = getItemTierInt(Tier)
	val maxTier = getItemTierInt(MaxTier)
	return if (maxTier == 0) tier else maxTier
}

val EFortDisplayTier?.displayName: FText get() = when (this) {
	EFortDisplayTier.Handmade -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Handmade", "Handmade")
	EFortDisplayTier.Copper -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Copper", "Copper")
	EFortDisplayTier.Silver -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Silver", "Silver")
	EFortDisplayTier.Malachite -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Malachite", "Malachite")
	EFortDisplayTier.Obsidian -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Obsidian", "Obsidian")
	EFortDisplayTier.Shadowshard -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Shadowshard", "Shadowshard")
	EFortDisplayTier.Brightcore -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Brightcore", "Brightcore")
	EFortDisplayTier.Sunbeam -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Sunbeam", "Sunbeam")
	EFortDisplayTier.Spectrolite -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Spectrolite", "Spectrolite")
	EFortDisplayTier.Moonglow -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Moonglow", "Moonglow")
	else -> FText("Fort.Weapon.Defaults", "EFortDisplayTier.Invalid", "Invalid")
}