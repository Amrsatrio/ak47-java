package com.tb24.discordbot.util

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.utils.AttachmentOption
import net.dv8tion.jda.internal.utils.Checks
import java.io.ByteArrayInputStream
import java.io.InputStream

class AttachmentUpload {
	val data: InputStream
	val name: String
	val options: Array<out AttachmentOption>

	constructor(data: InputStream, name: String, vararg options: AttachmentOption) {
		this.data = data
		this.name = name
		this.options = options
	}

	constructor(data: ByteArray, name: String, vararg options: AttachmentOption) {
		Checks.check(data.size <= Message.MAX_FILE_SIZE, "File may not exceed the maximum file length of %d bytes!", Message.MAX_FILE_SIZE)
		this.data = ByteArrayInputStream(data)
		this.name = name
		this.options = options
	}
}