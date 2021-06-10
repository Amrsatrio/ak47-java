package com.tb24.discordbot.ui

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.fn.model.FortItemStack
import com.tb24.fn.model.mcpprofile.McpProfile
import me.fungames.jfortniteparse.fort.enums.EFortStatType
import net.dv8tion.jda.api.entities.Emote
import kotlin.jvm.JvmField as F

class ResearchViewController {
	@F var resourceCollectorItem: FortItemStack? = null
	@F var points = 0
	@F var collected = 0
	@F val costs = mutableMapOf<EFortStatType, Int>()
	@F val icons = mutableMapOf<EFortStatType, Emote>()

	constructor(campaign: McpProfile) {
		populateItems(campaign)
	}

	@Synchronized
	fun populateItems(campaign: McpProfile) {
		points = 0
		for (item in campaign.items.values) {
			if (item.templateId == "CollectedResource:Token_collectionresource_nodegatetoken01") {
				resourceCollectorItem = item
			} else if (item.templateId == "Token:collectionresource_nodegatetoken01") {
				points += item.quantity
			}
		}
		if (resourceCollectorItem == null) {
			throw SimpleCommandExceptionType(LiteralMessage("Please complete the Audition quest (one quest after Stonewood SSD 3) to unlock Research.")).create()
		}
	}
}