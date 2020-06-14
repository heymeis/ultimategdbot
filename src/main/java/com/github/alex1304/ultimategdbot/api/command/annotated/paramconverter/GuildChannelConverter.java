package com.github.alex1304.ultimategdbot.api.command.annotated.paramconverter;

import com.github.alex1304.ultimategdbot.api.command.Context;
import com.github.alex1304.ultimategdbot.api.util.DiscordParser;

import discord4j.core.object.entity.channel.GuildChannel;
import reactor.core.publisher.Mono;

public class GuildChannelConverter implements ParamConverter<GuildChannel> {

	@Override
	public Mono<GuildChannel> convert(Context ctx, String input) {
		return Mono.justOrEmpty(ctx.event().getGuildId())
				.flatMap(guildId -> DiscordParser.parseGuildChannel(ctx, ctx.bot(), guildId, input))
				.switchIfEmpty(Mono.error(new RuntimeException(ctx.translate("generic", "channel_outside_of_guild"))));
	}

	@Override
	public Class<GuildChannel> type() {
		return GuildChannel.class;
	}
}