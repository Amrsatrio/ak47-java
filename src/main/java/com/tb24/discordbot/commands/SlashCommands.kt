package com.tb24.discordbot.commands

import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.*

abstract class CommandBuilder<SourceType, ResultType, ThisType : CommandBuilder<SourceType, ResultType, ThisType>> {
	var name: String
	var description: String
	val options = mutableListOf<OptionData>()
	var command: ((SourceType) -> Int)? = null

	constructor(name: String, description: String) {
		this.name = name
		this.description = description
	}

	abstract fun then(subcommand: CommandBuilder<SourceType, *, *>): ThisType

	fun option(type: OptionType, name: String, description: String, isRequired: Boolean = false): ThisType {
		options.add(OptionData(type, name, description, isRequired))
		return getThis()
	}

	fun executes(command: (SourceType) -> Int): ThisType {
		this.command = command
		return getThis()
	}

	abstract fun build(): ResultType
	protected abstract fun getThis(): ThisType
}

class BaseCommandBuilder<SourceType>(name: String, description: String) : CommandBuilder<SourceType, CommandData, BaseCommandBuilder<SourceType>>(name, description) {
	val subcommands = mutableMapOf<String, SubcommandBuilder<SourceType>>()
	val subcommandGroups = mutableMapOf<String, SubcommandGroupBuilder<SourceType>>()

	override fun then(subcommand: CommandBuilder<SourceType, *, *>): BaseCommandBuilder<SourceType> {
		if (subcommand is SubcommandBuilder<SourceType>) {
			subcommands[subcommand.name] = subcommand
		} else if (subcommand is SubcommandGroupBuilder<SourceType>) {
			subcommandGroups[subcommand.name] = subcommand
		}
		return this
	}

	override fun build(): CommandData {
		val data = Commands.slash(name, description)
		if (options.isNotEmpty()) data.addOptions(options)
		subcommands.values.forEach { data.addSubcommands(it.build()) }
		subcommandGroups.values.forEach { data.addSubcommandGroups(it.build()) }
		return data
	}

	override fun getThis() = this
}

class SubcommandBuilder<T>(name: String, description: String) : CommandBuilder<T, SubcommandData, SubcommandBuilder<T>>(name, description) {
	override fun then(subcommand: CommandBuilder<T, *, *>) = throw UnsupportedOperationException("Subcommands cannot have children")
	override fun build() = SubcommandData(name, description)

	override fun getThis() = this
}

class SubcommandGroupBuilder<T>(name: String, description: String) : CommandBuilder<T, SubcommandGroupData, SubcommandGroupBuilder<T>>(name, description) {
	val subcommands = mutableMapOf<String, SubcommandBuilder<T>>()

	override fun then(subcommand: CommandBuilder<T, *, *>): SubcommandGroupBuilder<T> {
		if (subcommand is SubcommandBuilder<T>) {
			subcommands[subcommand.name] = subcommand
		} else {
			throw UnsupportedOperationException("Subcommand groups can only have subcommands")
		}
		return this
	}

	override fun build(): SubcommandGroupData {
		val data = SubcommandGroupData(name, description)
		subcommands.values.forEach { data.addSubcommands(it.build()) }
		return data
	}

	override fun getThis() = this
}