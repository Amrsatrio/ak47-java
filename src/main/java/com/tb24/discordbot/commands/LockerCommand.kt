package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.GridSlot
import com.tb24.discordbot.createAttachmentOfIcons
import com.tb24.discordbot.util.*
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.commands.QueryProfile
import com.tb24.fn.util.Formatters
import com.tb24.fn.util.getPreviewImagePath
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import net.dv8tion.jda.api.entities.Message
import okhttp3.Request
import java.util.*
import java.util.concurrent.CompletableFuture

class LockerCommand : BrigadierCommand("locker", "Shows your BR locker in form of an image.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting cosmetics")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val ctgs: Map<String, MutableSet<FortItemStack>> = mapOf(
				"AthenaCharacter" to mutableSetOf(),
				"AthenaBackpack" to mutableSetOf(),
				"AthenaPickaxe" to mutableSetOf(),
				"AthenaGlider" to mutableSetOf(),
				"AthenaSkyDiveContrail" to mutableSetOf(),
				"AthenaDance" to mutableSetOf(),
				"AthenaItemWrap" to mutableSetOf(),
				"AthenaMusicPack" to mutableSetOf(),
				//"AthenaLoadingScreen" to mutableSetOf()
			)
			for (item in athena.items.values) {
				val ids = ctgs[item.primaryAssetType]
				if (ids != null && (item.primaryAssetType != "AthenaDance" || item.primaryAssetName.startsWith("eid_"))) {
					ids.add(item)
				}
			}
			source.loading("Generating and uploading images")
			CompletableFuture.allOf(
				perform(source, "Outfits", ctgs["AthenaCharacter"]),
				perform(source, "Back Blings", ctgs["AthenaBackpack"]),
				perform(source, "Harvesting Tools", ctgs["AthenaPickaxe"]),
				perform(source, "Gliders", ctgs["AthenaGlider"]),
				perform(source, "Contrails", ctgs["AthenaSkyDiveContrail"]),
				perform(source, "Emotes", ctgs["AthenaDance"]),
				perform(source, "Wraps", ctgs["AthenaItemWrap"]),
				perform(source, "Musics", ctgs["AthenaMusicPack"]),
				//perform(source, "Loading Screens", ctgs["AthenaLoadingScreen"])
			).await()
			source.complete("✅ All images have been sent successfully.")
			Command.SINGLE_SUCCESS
		}
}

class ExclusivesCommand : BrigadierCommand("exclusives", "Shows your exclusive cosmetics in an image.") {
	private val exclusivesOverride = listOf(
		"AthenaBackpack:bid_055_psburnout",
		"AthenaBackpack:bid_138_celestial",
		"AthenaBackpack:bid_142_raptorarcticcamo",
		"AthenaBackpack:bid_169_mathmale",
		"AthenaBackpack:bid_224_techopsblue",
		"AthenaBackpack:bid_234_speedymidnight",
		"AthenaBackpack:bid_249_streetopsstealth",
		"AthenaBackpack:bid_288_cyberscavengerfemaleblue",
		"AthenaBackpack:bid_346_blackwidowrogue",
		"AthenaBackpack:bid_448_techopsbluefemale",
		"AthenaBackpack:bid_482_longshorts",
		"AthenaBackpack:bid_521_wildcat",
		"AthenaCharacter:cid_017_athena_commando_m",
		"AthenaCharacter:cid_028_athena_commando_f",
		"AthenaCharacter:cid_035_athena_commando_m_medieval",
		"AthenaCharacter:cid_039_athena_commando_f_disco",
		"AthenaCharacter:cid_052_athena_commando_f_psblue",
		"AthenaCharacter:cid_085_athena_commando_m_twitch",
		"AthenaCharacter:cid_089_athena_commando_m_retrogrey",
		"AthenaCharacter:cid_113_athena_commando_m_blueace",
		"AthenaCharacter:cid_114_athena_commando_f_tacticalwoodland",
		"AthenaCharacter:cid_138_athena_commando_m_psburnout",
		"AthenaCharacter:cid_174_athena_commando_f_carbidewhite",
		"AthenaCharacter:cid_175_athena_commando_m_celestial",
		"AthenaCharacter:cid_183_athena_commando_m_modernmilitaryred",
		"AthenaCharacter:cid_261_athena_commando_m_raptorarcticcamo",
		"AthenaCharacter:cid_296_athena_commando_m_math",
		"AthenaCharacter:cid_313_athena_commando_m_kpopfashion",
		"AthenaCharacter:cid_342_athena_commando_m_streetracermetallic",
		"AthenaCharacter:cid_371_athena_commando_m_speedymidnight",
		"AthenaCharacter:cid_386_athena_commando_m_streetopsstealth",
		"AthenaCharacter:cid_434_athena_commando_f_stealthhonor",
		"AthenaCharacter:cid_441_athena_commando_f_cyberscavengerblue",
		"AthenaCharacter:cid_479_athena_commando_f_davinci",
		"AthenaCharacter:cid_516_athena_commando_m_blackwidowrogue",
		"AthenaCharacter:cid_619_athena_commando_f_techllama",
		"AthenaCharacter:cid_657_athena_commando_f_techopsblue",
		"AthenaCharacter:cid_711_athena_commando_m_longshorts",
		"AthenaCharacter:cid_757_athena_commando_f_wildcat",
		"AthenaCharacter:cid_783_athena_commando_m_aquajacket",
		"AthenaCharacter:cid_926_athena_commando_f_streetfashiondiamond",
		"AthenaDance:eid_davinci",
		"AthenaDance:eid_goodvibes",
		"AthenaDance:eid_kpopdance03",
		"AthenaDance:eid_playereleven",
		"AthenaDance:spid_066_llamalaxy",
		"AthenaGlider:founderglider",
		"AthenaGlider:founderumbrella",
		"AthenaGlider:glider_id_001",
		"AthenaGlider:glider_id_013_psblue",
		"AthenaGlider:glider_id_056_carbidewhite",
		"AthenaGlider:glider_id_067_psburnout",
		"AthenaGlider:glider_id_074_raptorarcticcamo",
		"AthenaGlider:glider_id_090_celestial",
		"AthenaGlider:glider_id_131_speedymidnight",
		"AthenaGlider:glider_id_137_streetopsstealth",
		"AthenaGlider:glider_id_137_streetopsstealth",
		"AthenaGlider:glider_id_217_longshortsmale",
		"AthenaGlider:glider_warthog",
		"AthenaItemWrap:wrap_121_techopsblue",
		"AthenaPickaxe:pickaxe_id_013_teslacoil",
		"AthenaPickaxe:pickaxe_id_039_tacticalblack",
		"AthenaPickaxe:pickaxe_id_077_carbidewhite",
		"AthenaPickaxe:pickaxe_id_088_psburnout",
		"AthenaPickaxe:pickaxe_id_097_raptorarcticcamo",
		"AthenaPickaxe:pickaxe_id_116_celestial",
		"AthenaPickaxe:pickaxe_id_153_roseleader",
		"AthenaPickaxe:pickaxe_id_178_speedymidnight",
		"AthenaPickaxe:pickaxe_id_189_streetopsstealth",
		"AthenaPickaxe:pickaxe_id_189_streetopsstealth",
		"AthenaPickaxe:pickaxe_id_237_warpaint",
		"AthenaPickaxe:pickaxe_id_256_techopsblue",
		"AthenaPickaxe:pickaxe_id_294_candycane",
		"AthenaPickaxe:pickaxe_id_464_longshortsmale",
		"AthenaPickaxe:pickaxe_id_stw001_tier_1",
		"AthenaPickaxe:pickaxe_id_stw006_tier_7",
		"AthenaPickaxe:pickaxe_id_stw007_basic",
		"AthenaPickaxe:pickaxe_lockjaw",
		"AthenaSkyDiveContrail:trails_id_019_psburnout",
		"AthenaSkyDiveContrail:trails_id_059_sony2",
		"AthenaSkyDiveContrail:trails_id_091_longshorts",
		"CosmeticVariantToken:vtid_052_skull_trooper_redflames",
		"CosmeticVariantToken:vtid_389_halloween_stylec",
		"CosmeticVariantToken:vtid_828_m_jupiter_styleb",
		"CosmeticVariantToken:vtid_837_m_historian_styleb",
	)

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.ensureSession()
			source.loading("Getting cosmetics")
			source.api.profileManager.dispatchClientCommandRequest(QueryProfile(), "athena").await()
			val athena = source.api.profileManager.getProfileData("athena")
			val exclusivesResponse = source.client.okHttpClient.newCall(Request.Builder().url("https://fort-api.com/exclusives/list").build()).execute()
			val exclusiveTemplateIds = if (exclusivesResponse.isSuccessful) {
				val out = mutableListOf<String>()
				exclusivesResponse.body()!!.charStream().use { it.forEachLine(out::add) }
				out
			} else exclusivesOverride
			val items = athena.items.values.filter { item -> exclusiveTemplateIds.any { it.equals(item.templateId, true) } }
			perform(source, "Exclusives", items).await()
			source.loadingMsg!!.delete().queue()
			Command.SINGLE_SUCCESS
		}
}

private fun perform(source: CommandSourceStack, name: String, ids: Collection<FortItemStack>?) = CompletableFuture.supplyAsync {
	if (ids.isNullOrEmpty()) {
		return@supplyAsync null
	}
	val slots = mutableListOf<GridSlot>()
	for (item in ids.sortedWith(SimpleAthenaLockerItemComparator())) {
		val itemData = item.defData ?: return@supplyAsync null
		slots.add(GridSlot(
			image = item.getPreviewImagePath()?.load<UTexture2D>()?.toBufferedImage(),
			name = item.displayName,
			rarity = itemData.Rarity
		))
	}
	var png: ByteArray
	var scale = 1f
	do {
		png = createAttachmentOfIcons(slots, "locker", scale)
		//println("png size ${png.size} scale $scale")
		scale -= 0.2f
	} while (png.size > Message.MAX_FILE_SIZE && scale >= 0.2f)
	source.channel.sendMessage("**$name** (${Formatters.num.format(ids.size)})")
		.addFile(png, "$name-${source.api.currentLoggedIn.id}.png").complete()
}