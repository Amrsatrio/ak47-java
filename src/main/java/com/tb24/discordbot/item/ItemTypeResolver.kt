package com.tb24.discordbot.item

import com.tb24.discordbot.L10N
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.getPathName
import com.tb24.fn.util.getString
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortItemType
import me.fungames.jfortniteparse.fort.exports.*
import me.fungames.jfortniteparse.fort.objects.ItemCategory
import me.fungames.jfortniteparse.ue4.objects.core.i18n.FText
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTagContainer


class ItemTypeResolver {
	@JvmField var leftImg: String? = null
	@JvmField var middleImg: String? = null
	@JvmField var rightImg: String? = null
	@JvmField var detailBoxImg: String? = null
	@JvmField var asText: FText? = null
	@JvmField var asTextSub: FText? = null
	@JvmField var secondaryCategory: ItemCategory? = null
	@JvmField var tertiaryCategory: ItemCategory? = null

	companion object {
		private val itemCategories by lazy { loadObject<FortItemCategory>("/Game/Items/ItemCategories.ItemCategories")!! }

		@JvmStatic
		fun matchPrimaryCategory(itemType: EFortItemType) =
			itemCategories.PrimaryCategories.firstOrNull { it.CategoryType == itemType }

		@JvmStatic
		fun matchCategory(tags: FGameplayTagContainer?, bTertiary: Boolean = false): ItemCategory? {
			if (tags == null) {
				return null
			}
			val categories = if (bTertiary) itemCategories.TertiaryCategories else itemCategories.SecondaryCategories
			return categories.firstOrNull { ctg -> ctg.TagContainer.gameplayTags.any { ctgTag -> tags.any { it.toString().startsWith(ctgTag.toString(), true) } } }
		}

		@JvmStatic
		fun matchCategory(tag: String?, bTertiary: Boolean = false): ItemCategory? {
			if (tag == null) {
				return null
			}
			val categories = if (bTertiary) itemCategories.TertiaryCategories else itemCategories.SecondaryCategories
			return categories.firstOrNull { ctg -> ctg.TagContainer.gameplayTags.any { tag.startsWith(it.toString(), true) } }
		}

		@JvmStatic
		fun resolveItemType(item: FortItemStack) = ItemTypeResolver().apply {
			val originalDefinition = item.defData
			val transformedDefinition = item.transformedDefData
			if (originalDefinition is FortHeroType) {
				secondaryCategory = matchCategory(originalDefinition.GameplayTags)?.apply {
					leftImg = CategoryBrush.Brush_XL.ResourceObject.getPathName()
					asText = CategoryName
					detailBoxImg = leftImg
				}
			} else if (transformedDefinition is FortWeaponItemDefinition) {
				asText = matchPrimaryCategory(when (transformedDefinition) {
					is FortTrapItemDefinition -> EFortItemType.Trap
					is FortWeaponMeleeItemDefinition -> EFortItemType.WeaponMelee
					is FortWeaponRangedItemDefinition -> EFortItemType.WeaponRanged
					else -> EFortItemType.Weapon
				})?.CategoryName
				secondaryCategory = matchCategory(transformedDefinition.GameplayTags)?.apply {
					detailBoxImg = CategoryBrush.Brush_XL.ResourceObject.getPathName()
					asTextSub = CategoryName
				}
				if (transformedDefinition is FortTrapItemDefinition) {
					leftImg = "/Game/UI/Foundation/Textures/Icons/ItemCategories/T-Icon-Traps-128.T-Icon-Traps-128"
					rightImg = detailBoxImg
				} else {
					leftImg = detailBoxImg
					transformedDefinition.AmmoData?.apply {
						rightImg = when (assetPathName.text) { // manual mapping for 128x128 icons, maximum in the ammo defs are 64x64
							"/Game/Items/Ammo/AmmoDataBulletsHeavy.AmmoDataBulletsHeavy" -> "/Game/UI/Foundation/Textures/Icons/Ammo/T-Icon-Ammo-Heavy-128.T-Icon-Ammo-Heavy-128"
							"/Game/Items/Ammo/AmmoDataBulletsLight.AmmoDataBulletsLight" -> "/Game/UI/Foundation/Textures/Icons/Ammo/T-Icon-Ammo-Light-128.T-Icon-Ammo-Light-128"
							"/Game/Items/Ammo/AmmoDataBulletsMedium.AmmoDataBulletsMedium" -> "/Game/UI/Foundation/Textures/Icons/Ammo/T-Icon-Ammo-Medium-128.T-Icon-Ammo-Medium-128"
							"/Game/Items/Ammo/AmmoDataShells.AmmoDataShells" -> "/Game/UI/Foundation/Textures/Icons/Ammo/T-Icon-Shell-128.T-Icon-Shell-128"
							"/Game/Items/Ammo/AmmoDataEnergyCell.AmmoDataEnergyCell", "/Game/Items/Ammo/AmmoInfiniteEnergy.AmmoInfiniteEnergy" -> "/Game/UI/Foundation/Textures/Icons/Ammo/T-Icon-Cell-128.T-Icon-Cell-128"
							"/Game/Items/Ammo/AmmoDataExplosive.AmmoDataExplosive", "/Game/Items/Ammo/AmmoDataM80.AmmoDataM80", "/Game/Athena/Items/Ammo/AmmoInfinite.AmmoInfinite" -> "/Game/UI/Foundation/Textures/Icons/Ammo/T-Icon-Special-128.T-Icon-Special-128"
							else -> null
						}
					}
				}
			} else if (originalDefinition is FortWorkerType) {
				val secondary = item.attributes.getString("personality") ?: originalDefinition.FixedPersonalityTag?.firstOrNull()?.toString()
				val tertiary: String?
				if (originalDefinition.bIsManager) {
					leftImg = "/Game/UI/Foundation/Textures/Icons/Stats/T-Icon-Leader-128.T-Icon-Leader-128"
					asText = L10N.Manager
					tertiary = item.attributes.getString("managerSynergy") ?: originalDefinition.ManagerSynergyTag?.firstOrNull()?.toString()
				} else {
					tertiary = item.attributes.getString("set_bonus") ?: originalDefinition.FixedSetBonusTag?.firstOrNull()?.toString()
				}
				secondaryCategory = matchCategory(secondary)?.apply {
					middleImg = CategoryBrush.Brush_XL.ResourceObject.getPathName()
				}
				tertiaryCategory = matchCategory(tertiary, true)?.apply {
					rightImg = CategoryBrush.Brush_XL.ResourceObject.getPathName()
				}
			} else if (originalDefinition is FortDefenderItemDefinition) {
				leftImg = "/Game/UI/Foundation/Textures/Icons/ItemCategories/T-Icon-Defenders-128.T-Icon-Defenders-128"
				tertiaryCategory = matchCategory(originalDefinition.GameplayTags, true)?.apply {
					asText = CategoryName
					rightImg = CategoryBrush.Brush_XL.ResourceObject.getPathName()
				}
			}
		}
	}
}