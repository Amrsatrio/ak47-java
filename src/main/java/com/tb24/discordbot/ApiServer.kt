package com.tb24.discordbot

import com.tb24.fn.EpicApi.GSON
import com.tb24.fn.model.EpicError
import com.tb24.uasset.AssetManager
import me.fungames.jfortniteparse.GDisableRecursiveImports
import me.fungames.jfortniteparse.fileprovider.AbstractFileProvider
import me.fungames.jfortniteparse.ue4.assets.IoPackage
import me.fungames.jfortniteparse.ue4.assets.exports.USoundWave
import me.fungames.jfortniteparse.ue4.assets.exports.UStaticMesh
import me.fungames.jfortniteparse.ue4.assets.exports.tex.UTexture2D
import me.fungames.jfortniteparse.ue4.converters.convert
import me.fungames.jfortniteparse.ue4.converters.meshes.convertMesh
import me.fungames.jfortniteparse.ue4.converters.meshes.psk.export
import me.fungames.jfortniteparse.ue4.converters.textures.toBufferedImage
import me.fungames.jfortniteparse.util.toPngArray
import org.eclipse.jetty.http.MimeTypes
import spark.kotlin.ignite
import java.io.File
import java.util.*

val cacheClearLock = Object()

fun main(args: Array<String>) {
	GDisableRecursiveImports = true
	AssetManager.INSTANCE.loadPaks()
	val app = ignite()
	val ipArg = args.getOrNull(0)
	if (!ipArg.isNullOrEmpty()) {
		app.ipAddress(ipArg)
	}
	val portArg = args.getOrNull(1)?.toIntOrNull()
	if (portArg != null) {
		app.port(portArg)
	}
	app.get("/api/assets/export") {
		val objectPath = request.queryParams("path") ?: halt(400, createError(
			"errors.com.tb24.asset_api.invalid_request",
			"Missing path query param"
		))
		if (objectPath[0] != '/' || objectPath.contains("/M_")) {
			println("Got a request with bad path: $objectPath")
			halt(400, createError(
				"errors.com.tb24.asset_api.invalid_request",
				"Bad path"
			))
		}
		var packagePath = objectPath
		val objectName: String
		val dotIndex = packagePath.indexOf('.')
		if (dotIndex == -1) { // use the package name as object name
			objectName = packagePath.substringAfterLast('/')
		} else { // packagePath.objectName
			objectName = packagePath.substring(dotIndex + 1)
			packagePath = packagePath.substring(0, dotIndex)
		}
		val packageName = packagePath.substringAfterLast('/')
		val dir = if (objectName == packageName) {
			packagePath.substringBeforeLast('/')
		} else {
			packagePath
		}
		val cacheDir = File("ExportCache", dir)
		/*cacheDir.listFiles()?.forEach {
			if (it.nameWithoutExtension.equals(objectName, false)) {
				response.header("Content-Disposition", "inline; filename=\"${it.name}\"")
				response.type(MimeTypes.getDefaultMimeByExtension(it.name))
				return@get it.readBytes()
			}
		}*/
		val obj = runCatching { AssetManager.INSTANCE.provider.loadObject(objectPath) }
			.getOrElse {
				halt(500, createError(
					"errors.com.tb24.asset_api.load_failed",
					"Failed to load package: %s",
					it.toString()
				))
			}
			?: halt(400, createError(
				"errors.com.tb24.asset_api.load_failed",
				"The package was loaded, but the object wasn't found"
			))
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
			else -> halt(400, createError(
				"errors.com.tb24.asset_api.invalid_export_type",
				"%s is not an exportable type",
				obj.exportType
			))
		}
		synchronized(cacheClearLock) {
			(AssetManager.INSTANCE.provider as AbstractFileProvider).asyncPackageLoader.globalPackageStore.apply {
				importStore.publicExportObjects.clear()
				loadedPackageStore.remove((obj.owner as IoPackage).importStore.desc.diskPackageId)
			}
		}
		val cacheFile = File(cacheDir, fileName)
		cacheFile.parentFile.mkdirs()
		cacheFile.writeBytes(data)
		response.header("Content-Disposition", "inline; filename=\"$fileName\"")
		response.type(MimeTypes.getDefaultMimeByExtension(fileName))
		data
	}
	app.get("/api/assets/dump") {
		"\"u suc\""
	}
	app.before {
		val corrId = request.headers("X-Epic-Correlation-ID") ?: UUID.randomUUID().toString()
		response.header("X-Epic-Correlation-ID", corrId)
		response.type("application/json")
	}
	app.service.exception(Exception::class.java) { e, req, res ->
		val corrId = res.raw().getHeader("X-Epic-Correlation-ID")
		System.err.println("Unhandled exception. Tracking ID: $corrId")
		e.printStackTrace()
		res.body(createError(
			"errors.com.tb24.common.server_error",
			"Sorry an error occurred and we were unable to resolve it (tracking id: [%s])",
			corrId
		))
	}
	app.notFound {
		createError(
			"errors.com.tb24.common.not_found",
			"Sorry the resource you were trying to find could not be found"
		)
	}
}

inline fun createError(errorCode: String, errorMessage: String, vararg format: String): String {
	return GSON.toJson(EpicError().also {
		it.errorCode = errorCode
		it.errorMessage = errorMessage.format(*format)
		it.messageVars = format
	})
}

fun halt(code: Int, body: String): Nothing {
	spark.kotlin.halt(code, body)
	throw AssertionError("halt() should throw an exception")
}