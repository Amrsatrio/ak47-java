package com.tb24.discordbot.commands

import com.google.common.collect.HashBasedTable
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.BoolArgumentType.getBool
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.addFieldSeparate
import com.tb24.discordbot.util.commandName
import com.tb24.discordbot.util.forRarity
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
import me.fungames.jfortniteparse.fort.enums.EFortRarity
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition
import me.fungames.jfortniteparse.fort.objects.rows.FortLootPackageData
import me.fungames.jfortniteparse.fort.objects.rows.FortLootTierData
import me.fungames.jfortniteparse.ue4.assets.exports.UDataTable
import me.fungames.jfortniteparse.ue4.assets.util.mapToClass
import net.dv8tion.jda.api.EmbedBuilder
import java.util.*
import java.util.function.Consumer
import kotlin.system.exitProcess

fun main() {
	AssetManager.INSTANCE.loadPaks()
	val context = LootContext()
	context.addLootPackages(loadObject<UDataTable>("/Game/Items/DataTables/AthenaLootPackages_Client"))
	context.addLootTiers(loadObject<UDataTable>("/Game/Items/DataTables/AthenaLootTierData_Client"))
	val random = Random(96882)
	val tierGroup = "Loot_AthenaTreasure"
	val outItems = mutableListOf<FortItemStack>()
	val tier = context.pickTier(tierGroup, random)!!
	tier.roll(context, random, outItems::add)
	println("\n\n\n\n\nRoll: $tierGroup")
	outItems.forEach {
		println("%,3d x [%s] %s".format(it.quantity, it.rarity, it.displayName))
	}
	exitProcess(0)
}

class RollCommand : BrigadierCommand("roll", "Simulate a given loot pool.") {
	val context by lazy {
		val context = LootContext()
		context.addLootPackages(loadObject<UDataTable>("/Game/Items/DataTables/AthenaLootPackages_Client.AthenaLootPackages_Client"))
		context.addLootTiers(loadObject<UDataTable>("/Game/Items/DataTables/AthenaLootTierData_Client.AthenaLootTierData_Client"))
		context.addLootPackages(loadObject<UDataTable>("/PrimalGameplay/DataTables/AthenaLootPackages_Client.AthenaLootPackages_Client"))
		context.addLootTiers(loadObject<UDataTable>("/PrimalGameplay/DataTables/AthenaLootTierData_Client.AthenaLootTierData_Client"))
		// TODO don't hardcode the game feature paths by enumerating the plugins
		context
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("loot tier group", word())
			.executes { roll(it.source, getString(it, "loot tier group")) }
			.then(argument("seed", greedyString())
				.executes {
					val seed = getString(it, "seed")
					roll(it.source, getString(it, "loot tier group"), seed.toLongOrNull() ?: seed.hashCode().toLong())
				}
			)
			.then(literal("info")
				.executes { info(it, getString(it, "loot tier group")) }
				.then(argument("show disabled?", bool())
					.executes { info(it, getString(it, "loot tier group"), getBool(it, "show disabled?")) }
				)
			)
		)
		.then(literal("available")
			.executes {
				it.source.channel.sendFile(context.lootTiers.keys.sorted().joinToString("\n").toByteArray(), "available_loot_tier_groups.txt").complete()
				Command.SINGLE_SUCCESS
			}
		)

	private fun roll(source: CommandSourceStack, inTierGroupName: String, seed: Long = Random().nextLong()): Int {
		val tierGroupName: String
		var tierOverride: String? = null
		if (inTierGroupName.contains('-')) {
			tierGroupName = inTierGroupName.substringBeforeLast('-')
			tierOverride = inTierGroupName.substringAfterLast('-')
		} else {
			tierGroupName = inTierGroupName
		}
		val tierGroup = context.lootTiers[tierGroupName.toLowerCase(Locale.ROOT)]
			?: throw SimpleCommandExceptionType(LiteralMessage("Loot tier group `$tierGroupName` not found")).create()
		val random = Random(seed)
		val tier = (if (tierOverride != null) tierGroup.firstOrNull { it.LootPackage.text.equals(tierOverride, true) } else tierGroup.pickOne(random))
			?: throw SimpleCommandExceptionType(LiteralMessage("Loot tier not found")).create()
		val outItems = mutableListOf<FortItemStack>()
		tier.roll(context, random, outItems::add)
		val displayItem = outItems.maxByOrNull { it.rarity }
		val descLines = mutableListOf<String>()
		descLines.add(tier.LootPackage.text)
		if (tier.LootTier > 0) {
			descLines.add("Tier: %,d".format(tier.LootTier))
		}
		val embed = EmbedBuilder()
			.setTitle("Roll: ${tier.TierGroup}")
			.setDescription(descLines.joinToString("\n"))
			.addFieldSeparate("You got", outItems, 0) {
				"%,d \u00d7 [%s] %s".format(it.quantity, it.rarity, it.displayName)
			}
			//.setFooter("Seed: %d".format(seed))
			.setThumbnail(Utils.benBotExportAsset(displayItem?.getPreviewImagePath(true)?.toString()))
		if (displayItem != null) {
			embed.setColor(rarityData.forRarity(displayItem.rarity).Color2.toFColor(true).toPackedARGB())
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}

	private fun info(c: CommandContext<CommandSourceStack>, inTierGroupName: String, showDisabled: Boolean = false): Int {
		val source = c.source
		val tierGroupName: String
		var tierOverride: String? = null
		if (inTierGroupName.contains('-')) {
			tierGroupName = inTierGroupName.substringBeforeLast('-')
			tierOverride = inTierGroupName.substringAfterLast('-')
		} else {
			tierGroupName = inTierGroupName
		}
		var tierGroup: List<FortLootTierData> = context.lootTiers[tierGroupName.toLowerCase(Locale.ROOT)]
			?: throw SimpleCommandExceptionType(LiteralMessage("Loot tier group `$tierGroupName` not found")).create()
		if (!showDisabled) {
			tierGroup = tierGroup.filter { it.Weight > 0f }
		}
		if (tierGroup.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("Loot tier group `$tierGroupName` has no loot tiers")).create()
		}
		var totalTierGroupWeight = 0f
		tierGroup.forEach { totalTierGroupWeight += it.Weight }
		val tier = when {
			tierGroup.size == 1 -> tierGroup[0]
			tierOverride != null -> {
				tierGroup.firstOrNull { it.LootPackage.text.equals(tierOverride, true) }
					?: throw SimpleCommandExceptionType(LiteralMessage("Loot tier with loot package name `$tierOverride` not found")).create()
			}
			else -> null
		}
		if (tier != null) {
			val lootPackages = context.lootPackages.row(tier.LootPackage.text.toLowerCase(Locale.ROOT))?.values?.flatten()
				?: throw SimpleCommandExceptionType(LiteralMessage("Loot package `${tier.LootPackage}` not found")).create()
			val embed = EmbedBuilder()
				.setAuthor(tier.TierGroup.text)
				.setTitle("%s %s".format(Formatters.percent.format(tier.Weight / totalTierGroupWeight), tier.LootPackage.text))
				.setColor(rarityData.forRarity(EFortRarity.values()[tier.LootTier]).Color2.toFColor(true).toPackedARGB())
			for (lootPackage in lootPackages) {
				val possibilities = mutableMapOf<String, Float>()
				lootPackage.populatePossibilities(possibilities, 1f, showDisabled)
				embed.addFieldSeparate("Category %,d".format(lootPackage.LootPackageCategory), possibilities.entries.sortedByDescending { it.value }, 0) {
					"`%7s` %s".format(Formatters.percent.format(it.value), it.key)
				}
			}
			source.complete(null, embed.build())
		} else {
			val possibilities = mutableMapOf<String, Float>()
			tierGroup.forEach { possibilities[it.LootPackage.text] = it.Weight / totalTierGroupWeight }
			source.complete(null, EmbedBuilder().setColor(COLOR_INFO)
				.setTitle(tierGroupName)
				.setDescription("This tier group has more than one tiers. Use `%s%s %s-<tier name> info` to view their details.".format(source.prefix, c.commandName, tierGroupName))
				.addFieldSeparate("%,d tiers".format(tierGroup.size), possibilities.entries.sortedByDescending { it.value }, 0) {
					"`%7s` %s".format(Formatters.percent.format(it.value), it.key)
				}.build())
		}
		return Command.SINGLE_SUCCESS
	}

	private fun FortLootPackageData.populatePossibilities(possibilities: MutableMap<String, Float>, totalFraction: Float, showDisabled: Boolean) {
		if (!showDisabled && Weight == 0f) {
			return
		}
		var key: String? = "%,d \u00d7 Unknown/Empty".format(Count)
		if (LootPackageCall.isNotEmpty()) {
			val called = context.lootPackages.row(LootPackageCall.toLowerCase(Locale.ROOT))?.values?.flatten()
			if (called != null) {
				var totalCalledWeight = 0f
				if (true) {
					called.forEach { totalCalledWeight += it.Weight }
					called.forEach {
						it.populatePossibilities(possibilities, (it.Weight / totalCalledWeight) / totalFraction, showDisabled)
					}
					key = null
				} else {
					key = "%,d \u00d7 Call: %s".format(Count, LootPackageCall)
				}
			}
		} else if (!ItemDefinition.assetPathName.isNone()) {
			val itemDef = loadObject<FortItemDefinition>(ItemDefinition.toString())
			if (itemDef != null) {
				val item = FortItemStack(itemDef, Count)
				key = "%,d \u00d7 [%s] %s".format(Count, item.rarity, item.displayName)
			}
		}
		if (key != null) {
			val currentValue = possibilities[key] ?: 0f
			possibilities[key] = currentValue + totalFraction
		}
	}
}

class LootContext {
	val lootTiers = mutableMapOf<String, MutableList<FortLootTierData>>()
	val lootPackages = HashBasedTable.create<String, Int, MutableList<FortLootPackageData>>()

	fun addLootTiers(lootTiersDataTable: UDataTable?) {
		lootTiersDataTable?.rows?.values?.forEach {
			val row = it.mapToClass<FortLootTierData>()
			lootTiers.getOrPut(row.TierGroup.text.toLowerCase(Locale.ROOT)) { mutableListOf() }.add(row)
		}
	}

	fun addLootPackages(lootPackagesDataTable: UDataTable?) {
		lootPackagesDataTable?.rows?.values?.forEach {
			val row = it.mapToClass<FortLootPackageData>()
			val key = row.LootPackageID.text.toLowerCase(Locale.ROOT)
			var list = lootPackages.get(key, row.LootPackageCategory)
			if (list == null) {
				list = mutableListOf()
				lootPackages.put(key, row.LootPackageCategory, list)
			}
			list.add(row)
		}
	}

	fun pickTier(tierGroup: String, random: Random) = lootTiers[tierGroup.toLowerCase(Locale.ROOT)]?.pickOne(random)
}

fun FortLootTierData.roll(context: LootContext, random: Random, consumer: Consumer<FortItemStack>) {
	val row = context.lootPackages.row(LootPackage.text.toLowerCase(Locale.ROOT)) ?: return
	// TODO LootPackageCategoryWeightArray, LootPackageCategoryMinArray, LootPackageCategoryMaxArray?
	for ((lootPackageCategory, lootPackageCategoryValues) in row) {
		for (lootPackage in lootPackageCategoryValues) {
			val item = lootPackage.createItem(context, random)
			if (item != null && item.quantity > 0) {
				consumer.accept(item)
			}
		}
	}
}

fun FortLootPackageData.createItem(context: LootContext, random: Random): FortItemStack? {
	if (LootPackageCall.isNotEmpty()) {
		return context.lootPackages.row(LootPackageCall.toLowerCase(Locale.ROOT))?.values?.flatten()?.pickOne(random)?.createItem(context, random)
	} else if (!ItemDefinition.assetPathName.isNone()) {
		return loadObject<FortItemDefinition>(ItemDefinition.toString())?.let { FortItemStack(it, Count) }
	}
	return null
}

fun List<FortLootTierData>.pickOne(random: Random) = pickFromWeighted(this, random) { it.Weight }
fun List<FortLootPackageData>.pickOne(random: Random) = pickFromWeighted(this, random) { it.Weight }

fun <T> pickFromWeighted(entries: List<T>, random: Random, getter: (T) -> Float): T? {
	var totalWeight = 0f
	entries.forEach { totalWeight += getter(it) }
	if (totalWeight != 0f) {
		if (entries.size == 1) {
			return entries[0]
		} else {
			var remaining = random.nextFloat() * totalWeight
			val iterator = entries.iterator()
			var entry: T
			do {
				if (!iterator.hasNext()) {
					return null
				}
				entry = iterator.next()
				remaining -= getter(entry)
			} while (remaining >= 0f)
			return entry
		}
	}
	return null
}