package com.tb24.discordbot.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.tb24.discordbot.commands.arguments.MentionArgument;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.ChannelType;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;

import java.util.Collection;

public class KickCommand {
	private static final SimpleCommandExceptionType NO_USERS_ERROR = new SimpleCommandExceptionType(new LiteralMessage("No users found"));
	private static final Dynamic2CommandExceptionType KICK_FAILED_ERROR = new Dynamic2CommandExceptionType((a, b) -> new LiteralMessage(String.format("***Failed* to kick %s:** %s", a, b)));

	public static void register(CommandDispatcher<CommandSourceStack> var0) {
		var0.register(LiteralArgumentBuilder.<CommandSourceStack>literal("kick")
			.requires(s -> s.isFromType(ChannelType.TEXT) && s.getMember().hasPermission(Permission.BAN_MEMBERS) && s.getGuild().getSelfMember().hasPermission(Permission.BAN_MEMBERS))
			.then(RequiredArgumentBuilder.<CommandSourceStack, MentionArgument.Resolver>argument("targets", MentionArgument.mention(Message.MentionType.USER))
				.executes(c -> execute(c.getSource(), MentionArgument.getMention(c, "targets"), "You have been kicked from the server."))
				.then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("reason", StringArgumentType.greedyString())
					.executes(c -> execute(c.getSource(), MentionArgument.getMention(c, "targets"), StringArgumentType.getString(c, "reason")))
				)
			)
		);
	}

	private static int execute(CommandSourceStack source, Collection<IMentionable> targets, String reason) throws CommandSyntaxException {
		if (targets == null || targets.isEmpty()) {
			throw NO_USERS_ERROR.create();
		}

		int success = 0;

		for (IMentionable target : targets) {
			User user = (User) target;

			try {
				source.getGuild().getMember(user).kick(reason).complete();
				source.getChannel().sendMessage(String.format("**Kicked %s:** %s", user.getName(), reason)).queue();
				++success;
			} catch (Exception e) {
				source.getChannel().sendMessage(String.format("***Failed* to kick %s:** %s", target.getAsMention(), e.getMessage() != null ? e.getMessage() : e.toString())).queue();
			}
		}

		return success;
	}
}
