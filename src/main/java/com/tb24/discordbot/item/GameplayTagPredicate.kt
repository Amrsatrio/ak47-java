package com.tb24.discordbot.item

import com.tb24.fn.model.FortItemStack
import me.fungames.jfortniteparse.ue4.objects.gameplaytags.FGameplayTag
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import java.util.*
import java.util.function.Predicate

open class GameplayTagPredicate(matchGameplayTag: String) : Predicate<FortItemStack> {
	private val matchGameplayTag = FGameplayTag(FName.dummy(matchGameplayTag.toLowerCase(Locale.ROOT)))

	override fun test(input: FortItemStack) = getGameplayTags(input)
		?.any { it.toString().toLowerCase(Locale.ROOT).startsWith(matchGameplayTag.toString()) } == true

	open fun getGameplayTags(input: FortItemStack) = input.defData?.GameplayTags
}