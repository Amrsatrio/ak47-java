package com.tb24.discordbot.tasks

import com.tb24.discordbot.DiscordBot
import com.tb24.discordbot.HttpException
import com.tb24.uasset.AssetManager
import me.fungames.jfortniteparse.ue4.objects.core.misc.FGuid
import java.util.*

class KeychainTask(val client: DiscordBot) : Runnable {
	override fun run() {
		DiscordBot.LOGGER.info("[FMcpKeychainHelper] Refresh")
		client.ensureInternalSession()
		val response = client.internalSession.api.fortniteService.storefrontKeychain(0).execute()
		if (response.isSuccessful) {
			DiscordBot.LOGGER.info("RefreshKeychain complete (HTTP ${response.code()})")
			response.body()!!.forEach(::handle)
		} else {
			val ex = HttpException(response)
			DiscordBot.LOGGER.warn("Unable to refresh keychain: ${ex.epicError.displayText} (HTTP ${response.code()})")
			throw ex
		}
	}

	fun handle(keyData: String) {
		val split = keyData.split(':')
		val guid = FGuid(split[0])
		val key = Base64.getDecoder().decode(split[1])
		val itemName = split.getOrNull(2)
		val provider = AssetManager.INSTANCE
		val requiredKeys = provider.requiredKeys()
		if (requiredKeys.contains(guid)) {
			val mounted = provider.submitKey(guid, key)
			/*if (mounted > 0) {
				//client.updateConfig()
				val readers = provider.mountedPaks().filter { it.pakInfo.encryptionKeyGuid == guid }
				val ioStores = provider.mountedIoStoreReaders().filter { it.encryptionKeyGuid == guid }
				handleDynamic(readers, ioStores, itemName)
			}*/
		}
	}

	/*fun handleDynamic(readers: List<PakFileReader>, ioStores: List<FIoStoreReaderImpl>, itemName: String?) {
		val files = mutableListOf<GameFile>()
		var encryptionKeyGuid: FGuid? = null
		var aesKeyStr: String? = null
		var fileName: String? = null
		readers.forEach {
			if (encryptionKeyGuid == null && aesKeyStr == null) {
				encryptionKeyGuid = it.pakInfo.encryptionKeyGuid
				aesKeyStr = it.aesKeyStr
			}
			if (fileName == null)
				fileName = it.fileName
			files.addAll(it.files)
		}
		ioStores.forEach {
			if (encryptionKeyGuid == null)
				encryptionKeyGuid = it.encryptionKeyGuid
			if (fileName == null)
				fileName = it.environment.path
			files.addAll(it.getFiles())
		}
	}*/
}

/*data class AesInfo internal constructor(val guid: FGuid, val aesKey: String, val itemName: String?, val pakName: String?, val reader: PakFileReader?, val cosmetics: Map<GameFile, ItemDefinitionContainer>, val variants: Map<GameFile, ItemDefinitionContainer>) {
	val isMainKey: Boolean
		get() = guid == FGuid.mainGuid
}*/