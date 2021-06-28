package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.StringArgument2.Companion.getString2
import com.tb24.discordbot.commands.arguments.StringArgument2.Companion.string2
import com.tb24.discordbot.util.Utils
import com.tb24.uasset.*
import me.fungames.jfortniteparse.fort.converters.createContainer
import me.fungames.jfortniteparse.fort.exports.FortItemDefinition
import me.fungames.jfortniteparse.ue4.assets.exports.UClass
import me.fungames.jfortniteparse.ue4.assets.exports.USoundWave
import me.fungames.jfortniteparse.ue4.assets.exports.UStaticMesh
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.convert
import me.fungames.jfortniteparse.ue4.converters.meshes.convertMesh
import me.fungames.jfortniteparse.ue4.converters.meshes.psk.export
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.util.toPngArray
import net.dv8tion.jda.api.entities.Message
import java.io.StringWriter

class DumpAssetCommand : BrigadierCommand("dump", "Shows the properties of an object/package's objects from the game files.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("path", greedyString())
			.executes { c ->
				val path = getString(c, "path")
				val toConvert: Any
				val fileName: String
				if (path.contains('.')) {
					val obj = runCatching { loadObject(path) }
						.getOrElse { throw SimpleCommandExceptionType(LiteralMessage("Failed to load object.\n```$it```")).create() }
						?: throw SimpleCommandExceptionType(LiteralMessage("Object not found.")).create()
					toConvert = obj
					fileName = obj.name
				} else {
					val pkg = runCatching { AssetManager.INSTANCE.loadGameFile(path) }
						.getOrElse { throw SimpleCommandExceptionType(LiteralMessage("Failed to load package.\n```$it```")).create() }
						?: throw SimpleCommandExceptionType(LiteralMessage("The package to load does not exist on disk or in the loader.")).create()
					toConvert = pkg.exports
					fileName = pkg.name.substringAfterLast('/').substringBeforeLast('.')
				}
				val s = JWPSerializer.GSON.newBuilder().setPrettyPrinting().create().toJson(toConvert)
				if (("```json\n\n```".length + s.length) > Message.MAX_CONTENT_LENGTH) {
					c.source.channel.sendFile(s.toByteArray(), "$fileName.json").complete()
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
				val obj = runCatching { loadObject(objectPath) }
					.getOrElse { throw SimpleCommandExceptionType(LiteralMessage("Failed to load package.\n```$it```")).create() }
					?: throw SimpleCommandExceptionType(LiteralMessage("Object not found.")).create()
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
					is FortItemDefinition -> {
						data = obj.createContainer().getImage(AssetManager.INSTANCE.locres).toPngArray()
						fileName = obj.name + ".png"
					}
					else -> throw SimpleCommandExceptionType(LiteralMessage("${obj.exportType} is not an exportable type.")).create()
				}
				c.source.channel.sendMessage("`${obj.exportType}'${obj.getPathName(null)}'`").addFile(data, fileName).complete()
				c.source.loadingMsg?.delete()?.complete()
				Command.SINGLE_SUCCESS
			}
		)
}

class DumpClassCommand : BrigadierCommand("dumpclass", "Class dump.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires { it.author.idLong in arrayOf(624299014388711455L, 299693897859465228L, 129267551673909249L, 476930709227962389L) }
		.then(argument("path", string2())
			.executes { dumpClass(it.source, getString2(it, "path")) }
			.then(argument("function body option", word())
				.executes { c ->
					val optionArg = getString(c, "function body option")
					val option = EFunctionBodyOption.values().firstOrNull { it.name.equals(optionArg, true) }
						?: throw SimpleCommandExceptionType(LiteralMessage("Invalid value `$optionArg`. Valid values are:```\n${EFunctionBodyOption.values().joinToString()}```")).create()
					dumpClass(c.source, getString(c, "path"), option)
				}
			)
		)

	private fun dumpClass(source: CommandSourceStack, path: String, functionBodyOption: EFunctionBodyOption = EFunctionBodyOption.NoBody): Int {
		var path = path
		if (!path.contains('.')) {
			path += ".${path.substringAfterLast('/')}_C"
		}
		val obj = runCatching { loadObject(path) }
			.getOrElse { throw SimpleCommandExceptionType(LiteralMessage("Failed to load object.\n```$it```")).create() }
			?: throw SimpleCommandExceptionType(LiteralMessage("Object not found. Possibly the package on the given path is not a class.")).create()
		val theClass = obj as? UClass
			?: throw SimpleCommandExceptionType(LiteralMessage("Not a class")).create()
		val sw = StringWriter()
		sw.append("// Class dump generated by ").append(source.client.discord.selfUser.asTag).append('\n')
		sw.append("// Discord: ").append(Utils.HOMEBASE_GUILD_INVITE).append("\n\n")
		val dumped = theClass.dumpToCpp(sw, functionBodyOption)
		source.channel.sendFile(dumped.toByteArray(), "${theClass.name}.h").complete()
		return Command.SINGLE_SUCCESS
	}
}