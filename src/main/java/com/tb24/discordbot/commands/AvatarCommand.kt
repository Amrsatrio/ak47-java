package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.discordbot.managers.ChannelsManager
import com.tb24.discordbot.managers.ChannelsManager.AvatarColor
import com.tb24.discordbot.util.*
import com.tb24.fn.model.UserSetting
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.MessageReaction
import net.dv8tion.jda.api.entities.User
import java.util.concurrent.CompletableFuture
import kotlin.math.max

class AvatarCommand : BrigadierCommand("avatar", "Manage your Party Hub avatar.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			val avatarKeys = source.session.channelsManager.getUserSettings(source.api.currentLoggedIn.id, ChannelsManager.KEY_AVATAR, ChannelsManager.KEY_AVATAR_BACKGROUND)
			source.complete(null, EmbedBuilder()
				.setTitle("Your Avatar")
				.setDescription("To change icon: `${source.prefix}${it.commandName} icon`\nTo change color: `${source.prefix}${it.commandName} color`")
				.setImage("https://cdn2.unrealengine.com/Kairos/portraits/${avatarKeys[0]}.png")
				.setColor(AvatarColor(avatarKeys[1]!!).dark)
				.build())
			Command.SINGLE_SUCCESS
		}
//		.then(literal("set")
//			.executes {
//				val source = it.source
//				source.ensureSession()
//				source.api.channelsService.UpdateUserSetting(source.api.currentLoggedIn.id, "")
//				Command.SINGLE_SUCCESS
//			}
//		)
		.then(literal("icon")
			.executes { picker(it.source, false) }
		)
		.then(literal("color")
			.executes { picker(it.source, true) }
		)

	private fun picker(source: CommandSourceStack, isColor: Boolean): Int {
		source.ensureSession()
		source.loading("Getting choices")
		val settingKey = if (isColor) ChannelsManager.KEY_AVATAR_BACKGROUND else ChannelsManager.KEY_AVATAR
		val settingKeyIndex = if (isColor) 1 else 0
		val avatarKeys = source.session.channelsManager.getUserSettings(source.api.currentLoggedIn.id, ChannelsManager.KEY_AVATAR, ChannelsManager.KEY_AVATAR_BACKGROUND)
		val current = avatarKeys[settingKeyIndex]
		val available = source.api.channelsService.QueryAvailableUserSettingValues(source.api.currentLoggedIn.id, settingKey).exec().body()!!.toList()
		val event = CompletableFuture<String?>()
		source.message.replyPaginated(available, 1, source.loadingMsg, max(available.indexOf(current), 0), AvatarReactions(available, event)) { content, page, pageCount ->
			val pageValue = content[0]
			MessageBuilder(EmbedBuilder()
				.setAuthor(source.api.currentLoggedIn.displayName)
				.apply {
					if (isColor) {
						val avatarColor = ChannelsManager.COLOR_SCHEMES[page]
						setTitle("Choose Background Color")
						setDescription(avatarColor.name)
						setColor(avatarColor.dark)
					} else {
						setTitle("Choose Avatar")
						setImage("https://cdn2.unrealengine.com/Kairos/portraits/$pageValue.png?preview=1") // use the low res preview because this is paginated
						setColor(AvatarColor(avatarKeys[1]!!).dark)
					}
				}
				.setFooter("%,d of %,d".format(page + 1, pageCount) + if (pageValue == current) " (current)" else "")
			).build()
		}
		source.loadingMsg = null
		val newSetting = runCatching { event.await() }.getOrNull() ?: return Command.SINGLE_SUCCESS
		source.loading("Applying")
		source.api.channelsService.UpdateUserSetting(source.api.currentLoggedIn.id, settingKey, UserSetting().apply { value = newSetting })
		source.session.channelsManager.put(source.api.currentLoggedIn.id, settingKey, newSetting)
		avatarKeys[settingKeyIndex] = newSetting
		source.complete(null, EmbedBuilder()
			.setAuthor(source.api.currentLoggedIn.displayName)
			.setTitle("✅ " + (if (isColor) "Updated avatar background" else "Updated avatar character"))
			.setImage("https://cdn2.unrealengine.com/Kairos/portraits/${avatarKeys[0]}.png")
			.setColor(AvatarColor(avatarKeys[1]!!).dark)
			.build())
		return Command.SINGLE_SUCCESS
	}

	private class AvatarReactions(val list: List<String>, val event: CompletableFuture<String?>) : PaginatorCustomReactions<String> {
		var confirmed = false

		override fun addReactions(reactions: MutableCollection<String>) {
			reactions.add("✅")
		}

		override fun handleReaction(collector: ReactionCollector, item: MessageReaction, user: User?, page: Int, pageCount: Int) {
			if (!confirmed && item.reactionEmote.name == "✅") {
				confirmed = true
				event.complete(list[page])
				collector.stop()
			}
		}

		override fun onEnd(collected: Map<Any, MessageReaction>, reason: CollectorEndReason) {
			event.complete(null)
		}
	}
}