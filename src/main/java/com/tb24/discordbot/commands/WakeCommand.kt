package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.StringArgument2
import me.fungames.jfortniteparse.util.parseHexBinary
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer

class WakeCommand : BrigadierCommand("wake", "Wake on LAN") {
	private val invalidMacError = SimpleCommandExceptionType(LiteralMessage("Invalid MAC address.")).create()

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("IP[:port]", StringArgument2.string2())
			.then(argument("MAC address", StringArgument2.string2())
				.executes { execute(it.source, StringArgumentType.getString(it, "IP[:port]"), StringArgumentType.getString(it, "MAC address")) }
			)
		)

	private fun execute(source: CommandSourceStack, ipPort: String, mac: String): Int {
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
}