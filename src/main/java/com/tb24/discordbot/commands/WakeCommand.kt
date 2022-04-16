package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.rethinkdb.RethinkDB.r
import com.tb24.discordbot.commands.arguments.StringArgument2
import com.tb24.discordbot.model.WakeEntry
import me.fungames.jfortniteparse.util.parseHexBinary
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer

class WakeCommand : BrigadierCommand("wake", "Wake on LAN", arrayOf("wol")) {
	private val invalidMacError = SimpleCommandExceptionType(LiteralMessage("Invalid MAC address.")).create()

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("IP[:port]", StringArgument2.string2())
			.executes { execute(it.source, StringArgumentType.getString(it, "IP[:port]")) }
			.then(argument("MAC address", StringArgument2.string2())
				.executes { execute(it.source, StringArgumentType.getString(it, "IP[:port]"), StringArgumentType.getString(it, "MAC address")) }
			)
		)
		.then(literal("create")
			.then(argument("IP[:port]", StringArgument2.string2())
				.then(argument("MAC address", StringArgument2.string2())
					.then(argument("name", StringArgumentType.string())
						.executes { save(it.source, StringArgumentType.getString(it, "IP[:port]"), StringArgumentType.getString(it, "MAC address"), StringArgumentType.getString(it, "name")) }
					)
				)
			)
		)
		.then(literal("remove")
			.then(argument("name", StringArgumentType.string())
				.executes { remove(it.source, StringArgumentType.getString(it, "name")) }
			)
		)
		.then(literal("delete")
			.then(argument("name", StringArgumentType.string())
				.executes { remove(it.source, StringArgumentType.getString(it, "name")) }
			)
		)
		.then(literal("list")
			.executes { list(it.source) }
		)

	private fun execute(source: CommandSourceStack, ipPort: String, mac: String? = null): Int {
		val db = r.table("wol").run(source.client.dbConn, WakeEntry::class.java)
		if (mac == null && (ipPort.split(".").size <= 1 || ipPort.split(":").size >= 2)) {
			val entry = db.toList().firstOrNull { it.registrantId == source.author.id && it.name == ipPort } ?: throw SimpleCommandExceptionType(LiteralMessage("You don't have an entry with this name.")).create()
			val macBytes = entry.mac.parseHexBinary()
			val buf = ByteBuffer.allocate(6 + 16 * macBytes.size)
			repeat(6) { buf.put(0xFF.toByte()) }
			repeat(16) { buf.put(macBytes) }
			val ip = entry.ipPort.split(":")[0]
			val port = entry.ipPort.split(":")[1].toInt()
			val address = InetAddress.getByName(ip)
			val packet = DatagramPacket(buf.array(), buf.array().size, address, port)
			try {
				DatagramSocket().use { it.send(packet) }
			} catch (e: Exception) {
				throw SimpleCommandExceptionType(LiteralMessage("Failed to send packet.")).create()
			}
			source.complete("✅")
			return Command.SINGLE_SUCCESS
		}
		if (mac == null) {
			throw SimpleCommandExceptionType(LiteralMessage("You must specify a MAC address.")).create()
		}
		val macBytes = try {
			mac.replace(":", "").replace("-", "").parseHexBinary()
		} catch (e: Exception) {
			throw invalidMacError
		}
		if (macBytes.size != 6) {
			throw invalidMacError
		}
		val buf = ByteBuffer.allocate(6 + 16 * macBytes.size)
		repeat(6) { buf.put(0xFF.toByte()) }
		repeat(16) { buf.put(macBytes) }
		val ip = ipPort.substringBefore(":")
		val port = ipPort.substringAfter(":").toIntOrNull() ?: 9
		val address = try {
			InetAddress.getByName(ip)
		} catch (e: UnknownHostException) {
			throw SimpleCommandExceptionType(LiteralMessage("Failed to resolve host.")).create()
		}
		val packet = DatagramPacket(buf.array(), buf.array().size, address, port)
		try {
			DatagramSocket().use { it.send(packet) }
		} catch (e: Exception) {
			throw SimpleCommandExceptionType(LiteralMessage("Failed to send packet.")).create()
		}
		source.complete("✅")
		return Command.SINGLE_SUCCESS
	}

	private fun save(source: CommandSourceStack, ipPort: String, mac: String, name: String): Int {
		val macBytes = try {
			mac.replace(":", "").replace("-", "").parseHexBinary()
		} catch (e: Exception) {
			throw invalidMacError
		}
		if (macBytes.size != 6) {
			throw invalidMacError
		}
		try {
			InetAddress.getByName(ipPort.substringBefore(":"))
		} catch (e: UnknownHostException) {
			throw SimpleCommandExceptionType(LiteralMessage("Failed to resolve host.")).create()
		}
		val db = r.table("wol").run(source.client.dbConn, WakeEntry::class.java).toList()
		if (db.firstOrNull { it.name == name && it.registrantId == source.author.id } != null) {
			throw SimpleCommandExceptionType(LiteralMessage("You already have an entry with that name.")).create()
		}
		val mac = mac.replace(":", "").replace("-", "").toUpperCase()
		r.table("wol").insert(WakeEntry(source.author.id, ipPort, mac, name)).run(source.client.dbConn)
		source.complete("✅ Saved", null)
		return Command.SINGLE_SUCCESS
	}

	private fun remove(source: CommandSourceStack, name: String): Int {
		val db = r.table("wol").run(source.client.dbConn, WakeEntry::class.java).toList()
		val entry = db.firstOrNull { it.name == name && it.registrantId == source.author.id} ?: throw SimpleCommandExceptionType(LiteralMessage("You don't have an entry with that name.")).create()
		r.table("wol").get(entry.id).delete().run(source.client.dbConn)
		source.complete("✅ Removed %s".format(name), null)
		return Command.SINGLE_SUCCESS
	}

	private fun list(source: CommandSourceStack): Int {
		val db = r.table("wol").run(source.client.dbConn, WakeEntry::class.java).toList()
		val entries = db.filter { it.registrantId == source.author.id }.map { it.name }
		if (entries.isEmpty()) {
			throw SimpleCommandExceptionType(LiteralMessage("You don't have any entries.")).create()
		} else {
			source.complete("Wake entries: %s".format(entries.joinToString(", ")), null)
			return Command.SINGLE_SUCCESS
		}
	}
}