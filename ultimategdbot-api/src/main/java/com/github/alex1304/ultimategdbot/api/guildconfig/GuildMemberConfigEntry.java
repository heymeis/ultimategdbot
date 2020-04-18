package com.github.alex1304.ultimategdbot.api.guildconfig;

import java.util.function.BiFunction;
import java.util.function.Function;

import discord4j.core.object.entity.Member;
import reactor.core.publisher.Mono;

public class GuildMemberConfigEntry extends AbstractConfigEntry<Member> {

	GuildMemberConfigEntry(GuildConfigurator<?> configurator, String displayName, String key, String prompt,
			Function<Object, ? extends Mono<Member>> valueGetter,
			BiFunction<Object, ? super Member, Object> valueSetter, Validator<Member> validator) {
		super(configurator, displayName, key, prompt, valueGetter, valueSetter, validator);
	}

	@Override
	public <R> Mono<R> accept(ConfigEntryVisitor<R> visitor) {
		return visitor.visit(this);
	}
	
	/**
	 * Creates a builder for this ConfigEntry implementation.
	 * 
	 * @param <D> the implementation type of {@link GuildConfigData} this entry
	 *            affects
	 * @param key the unique key identifying the built entry
	 * @return a new builder
	 */
	public static <D extends GuildConfigData<D>> ConfigEntryBuilder<D, Member> builder(String key) {
		return new ConfigEntryBuilder<>(GuildMemberConfigEntry::new, key);
	}
}