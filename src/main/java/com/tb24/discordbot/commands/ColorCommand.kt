package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.FloatArgumentType.floatArg
import com.mojang.brigadier.arguments.FloatArgumentType.getFloat
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import me.fungames.jfortniteparse.ue4.objects.core.math.FColor
import me.fungames.jfortniteparse.ue4.objects.core.math.FLinearColor
import me.fungames.jfortniteparse.util.printHexBinary
import net.dv8tion.jda.api.EmbedBuilder
import java.nio.ByteBuffer

class ColorCommand : BrigadierCommand("linearcolor", "Shows info of a UE4 FLinearColor") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.then(argument("red", floatArg(0f, 1f))
			.then(argument("green", floatArg(0f, 1f))
				.then(argument("blue", floatArg(0f, 1f))
					.executes { execute(it.source, FLinearColor(getFloat(it, "red"), getFloat(it, "green"), getFloat(it, "blue"))) }
					.then(argument("alpha", floatArg(0f, 1f))
						.executes { execute(it.source, FLinearColor(getFloat(it, "red"), getFloat(it, "green"), getFloat(it, "blue"), getFloat(it, "alpha"))) }
					)
				)
			)
		)

	fun execute(source: CommandSourceStack, color: FLinearColor): Int {
		val asSrgbColor = color.toFColor(true)
		source.complete(null, EmbedBuilder()
			.setTitle(color.toString())
			.setThumbnail("https://singlecolorimage.com/get/%06x/%dx%d".format(asSrgbColor.toPackedARGB() and 0xFFFFFF, 128, 128))
			.setColor(asSrgbColor.toPackedARGB() and 0xFFFFFF)
			.setDescription(ByteBuffer.allocate(4 * 4).putFloat(color.r).putFloat(color.g).putFloat(color.b).putFloat(color.a).array().printHexBinary())
			.addField("Quantize()", color.quantize().render(), false)
			.addField("QuantizeRound()", color.quantizeRound().render(), false)
			.addField("ToFColor(bSRGB=false)", color.toFColor(false).render(), false)
			.addField("ToFColor(bSRGB=true)", asSrgbColor.render(), false)
			.build())
		return Command.SINGLE_SUCCESS
	}

	fun FColor.render() = "%s (#%08X)".format(toString(), toPackedARGB())
}