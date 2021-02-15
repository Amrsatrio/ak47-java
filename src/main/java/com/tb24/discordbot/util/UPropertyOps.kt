package com.tb24.discordbot.util

import com.mojang.datafixers.util.Pair
import com.mojang.serialization.DataResult
import com.mojang.serialization.DataResult.error
import com.mojang.serialization.DataResult.success
import com.mojang.serialization.DynamicOps
import me.fungames.jfortniteparse.ue4.assets.objects.*
import me.fungames.jfortniteparse.ue4.assets.objects.FProperty.*
import me.fungames.jfortniteparse.ue4.objects.uobject.FName
import java.math.BigInteger
import java.util.stream.Collectors
import java.util.stream.Stream

class UPropertyOps : DynamicOps<FProperty> {
	override fun empty(): FProperty {
		TODO("Not yet implemented")
	}

	override fun <U : Any?> convertTo(outOps: DynamicOps<U>, input: FProperty): U {
		TODO("Not yet implemented")
	}

	override fun getNumberValue(input: FProperty): DataResult<Number> =
		when (input) {
			is Int8Property -> success(input.number)
			is Int16Property -> success(input.number)
			is IntProperty -> success(input.number)
			is Int64Property -> success(input.number)
			is ByteProperty -> success(input.byte.toShort())
			is UInt16Property -> success(input.number.toInt())
			is UInt32Property -> success(input.number.toLong())
			is UInt64Property -> success(BigInteger(input.number.toString()))
			else -> error("Not a number: ${input.javaClass.simpleName}")
		}

	override fun createNumeric(i: Number): FProperty {
		TODO("Not yet implemented")
	}

	override fun getStringValue(input: FProperty): DataResult<String> =
		when (input) {
			is StrProperty -> success(input.str)
			else -> error("Not a string: ${input.javaClass.simpleName}")
		}

	override fun createString(value: String): FProperty {
		TODO("Not yet implemented")
	}

	override fun mergeToList(list: FProperty, value: FProperty): DataResult<FProperty> {
		TODO("Not yet implemented")
	}

	override fun mergeToMap(map: FProperty, key: FProperty, value: FProperty): DataResult<FProperty> {
		TODO("Not yet implemented")
	}

	override fun getMapValues(input: FProperty): DataResult<Stream<Pair<FProperty, FProperty>>> {
		if (input is StructProperty) {
			val type = input.struct.structType
			if (type is FStructFallback) {
				return success(type.properties.stream().map { Pair(StrProperty(it.name.text), it.prop) })
			}
		}
		return error("Not a string: ${input.javaClass.simpleName}")
	}

	override fun createMap(map: Stream<Pair<FProperty, FProperty>>): FProperty {
		val props = mutableListOf<FPropertyTag>()
		map.forEach {
			val tag = FPropertyTag(FName.dummy(it.first.getTagTypeValue<String>()!!))
			tag.prop = it.second
			props.add(tag)
		}
		return StructProperty(UScriptStruct(FName.NAME_None, FStructFallback(props)))
	}

	override fun getStream(input: FProperty): DataResult<Stream<FProperty>> {
		TODO("Not yet implemented")
	}

	override fun createList(input: Stream<FProperty>) =
		ArrayProperty(UScriptArray(null, input.collect(Collectors.toList())))

	override fun remove(input: FProperty, key: String): FProperty {
		TODO("Not yet implemented")
	}
}