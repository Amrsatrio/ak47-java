package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.uasset.AssetManager
import com.tb24.uasset.JWPSerializer
import me.fungames.jfortniteparse.ue4.assets.exports.USoundWave
import me.fungames.jfortniteparse.ue4.assets.exports.UStaticMesh
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.convert
import me.fungames.jfortniteparse.ue4.converters.meshes.convertMesh
import me.fungames.jfortniteparse.ue4.converters.meshes.psk.export
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.entities.Message

class DumpAssetCommand : BrigadierCommand("dump", "Dump a package from the game files.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("package path", greedyString())
			.executes { c ->
				val packagePath = getString(c, "package path")
				if (packagePath.contains('.')) {
					throw SimpleCommandExceptionType(LiteralMessage("Not a valid package path. It should not contain extensions or object names.")).create()
				}
				val pkg = runCatching { AssetManager.INSTANCE.provider.loadGameFile(packagePath) }
					.getOrElse { throw SimpleCommandExceptionType(LiteralMessage("Failed to load package.\n```$it```")).create() }
				val s = JWPSerializer.GSON.newBuilder().setPrettyPrinting().create().toJson(pkg.exports)
				if (("```json\n\n```".length + s.length) > Message.MAX_CONTENT_LENGTH) {
					c.source.channel.sendFile(s.toByteArray(), pkg.fileName.substringAfterLast('/') + ".json").queue()
				} else {
					c.source.complete("```json\n$s\n```")
				}
				Command.SINGLE_SUCCESS
			}
		)
}

class ExportObjectCommand : BrigadierCommand("export", "Export an object from the game files.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("object path", greedyString())
			.executes { c ->
				val objectPath = getString(c, "object path")
				c.source.loading("Processing object")
				val obj = runCatching { AssetManager.INSTANCE.provider.loadObject(objectPath) }
					.getOrElse { throw SimpleCommandExceptionType(LiteralMessage("Failed to load package.\n```$it```")).create() }
					?: throw SimpleCommandExceptionType(LiteralMessage("The package was loaded, but the object wasn't found.")).create()
				val data: ByteArray
				val fileName: String
				when (obj) {
					is USoundWave -> {
						val converted = obj.convert()
						data = converted.data
						fileName = obj.name + '.' + converted.format.toLowerCase()
					}
					is UStaticMesh -> {
						val converted = obj.convertMesh().export(exportLods = false, exportMaterials = false)!!
						data = converted.pskx
						fileName = converted.fileName
					}
					is UTexture2D -> {
						data = obj.toBufferedImage().toPngArray()
						fileName = obj.name + ".png"
					}
					else -> throw SimpleCommandExceptionType(LiteralMessage("${obj.exportType} is not an exportable type.")).create()
				}
				c.source.channel.sendMessage("`${obj.exportType}'${obj.owner!!.fileName}.${obj.name}'`").addFile(data, fileName).complete()
				c.source.loadingMsg?.delete()?.complete()
				Command.SINGLE_SUCCESS
			}
		)
}