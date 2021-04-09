package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.forRarity
import com.tb24.discordbot.util.Utils
import com.tb24.discordbot.util.addFieldSeparate
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.util.getPreviewImagePath
import com.tb24.uasset.AssetManager
import com.tb24.uasset.loadObject
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

class RollCommand : BrigadierCommand("roll", "Simulate a given loot pool.", arrayOf("r")) {
	val context = LootContext()

	init {
		context.addLootPackages(loadObject<UDataTable>("/Game/Items/DataTables/AthenaLootPackages_Client.AthenaLootPackages_Client"))
		context.addLootTiers(loadObject<UDataTable>("/Game/Items/DataTables/AthenaLootTierData_Client.AthenaLootTierData_Client"))
	}

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("loot tier group", word())
			.executes { execute(it.source, getString(it, "loot tier group")) }
			.then(argument("seed", greedyString())
				.executes {
					val seed = getString(it, "seed")
					execute(it.source, getString(it, "loot tier group"), seed.toLongOrNull() ?: seed.hashCode().toLong())
				}
			)
		)
		.then(literal("available")
			.executes {
				it.source.channel.sendFile(context.lootTiers.keys.sorted().joinToString("\n").toByteArray(), "available_loot_tier_groups.txt").complete()
				Command.SINGLE_SUCCESS
			}
		)

	private fun execute(source: CommandSourceStack, tierGroupName: String, seed: Long = Random().nextLong()): Int {
		val random = Random(seed)
		val outItems = mutableListOf<FortItemStack>()
		val tierGroup = context.lootTiers[tierGroupName]
			?: throw SimpleCommandExceptionType(LiteralMessage("Loot tier group `$tierGroupName` not found")).create()
		val tier = tierGroup.pickOne(random)
		tier?.roll(context, random, outItems::add)
		val forDisplay = outItems.maxByOrNull { it.rarity }
		val descLines = mutableListOf<String>()
		if (tier != null && tier.LootTier > 0) {
			descLines.add("Tier: %,d".format(tier.LootTier))
		}
		val embed = EmbedBuilder()
			.setTitle("Roll: $tierGroupName")
			.setDescription(descLines.joinToString("\n"))
			.addFieldSeparate("You got", outItems, 0) {
				"%,d \u00d7 [%s] %s".format(it.quantity, it.rarity, it.displayName)
			}
			.setFooter("Seed: %d".format(seed))
			.setThumbnail(Utils.benBotExportAsset(forDisplay?.getPreviewImagePath(true)?.toString()))
		if (forDisplay != null) {
			embed.setColor(rarityData.forRarity(forDisplay.rarity).Color2.toFColor(true).toPackedARGB())
		}
		source.complete(null, embed.build())
		return Command.SINGLE_SUCCESS
	}
}

class LootContext {
	val lootTiers = mutableMapOf<String, MutableList<FortLootTierData>>()
	val lootPackages = mutableMapOf<String, MutableList<FortLootPackageData>>()

	fun addLootTiers(lootTiersDataTable: UDataTable?) {
		lootTiersDataTable?.rows?.values?.forEach {
			val row = it.mapToClass<FortLootTierData>()
			lootTiers.getOrPut(row.TierGroup.text) { mutableListOf() }.add(row)
		}
	}

	fun addLootPackages(lootPackagesDataTable: UDataTable?) {
		lootPackagesDataTable?.rows?.values?.forEach {
			val row = it.mapToClass<FortLootPackageData>()
			lootPackages.getOrPut(row.LootPackageID.text) { mutableListOf() }.add(row)
		}
	}

	fun pickTier(tierGroup: String, random: Random) = lootTiers[tierGroup]?.pickOne(random)
}

fun FortLootTierData.roll(context: LootContext, random: Random, consumer: Consumer<FortItemStack>) {
	val lootPackages = context.lootPackages[LootPackage.text] ?: emptyList()
	for (lootPackage in lootPackages) {
		val item = lootPackage.createItem(context, random)
		if (item != null && item.quantity > 0) {
			consumer.accept(item)
		}
	}
}

fun FortLootPackageData.createItem(context: LootContext, random: Random): FortItemStack? {
	if (LootPackageCall.isNotEmpty()) {
		return context.lootPackages[LootPackageCall]?.pickOne(random)?.createItem(context, random)
	} else if (!ItemDefinition.assetPathName.isNone()) {
		return FortItemStack(loadObject<FortItemDefinition>(ItemDefinition.toString()), Count)
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