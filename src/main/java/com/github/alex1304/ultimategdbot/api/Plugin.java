package com.github.alex1304.ultimategdbot.api;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

import com.github.alex1304.ultimategdbot.api.command.CommandProvider;
import com.github.alex1304.ultimategdbot.api.guildconfig.GuildConfigDao;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Represents a plugin. A plugin has a name and provides commands.
 */
public class Plugin {
	
	private final String name;
	private final Set<Class<? extends GuildConfigDao<?>>> guildConfigExtensions;
	private final CommandProvider commandProvider;
	private final Supplier<? extends Mono<Void>> onReady;
	
	private Plugin(String name, Set<Class<? extends GuildConfigDao<?>>> guildConfigExtensions,
			CommandProvider commandProvider, Supplier<? extends Mono<Void>> onReady) {
		this.name = name;
		this.guildConfigExtensions = guildConfigExtensions;
		this.commandProvider = commandProvider;
		this.onReady = onReady;
	}
	
	/**
	 * Gets a Mono that should be subscribed to when the bot is ready.
	 * 
	 * @return a Mono
	 */
	public Mono<Void> onReady() {
		return onReady.get();
	}

	/**
	 * Gets the name of the plugin.
	 * 
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Gets the classes of database extensions useful to retrieve configuration for
	 * a guild.
	 * 
	 * @return a Set containing the classes of the guild configuration extensions
	 */
	public Set<Class<? extends GuildConfigDao<?>>> getGuildConfigExtensions() {
		return guildConfigExtensions;
	}

	/**
	 * Gets the command provider for this plugin.
	 * 
	 * @return the command provider
	 */
	public CommandProvider getCommandProvider() {
		return commandProvider;
	}
	
	/**
	 * Gets the Git properties for this plugin. By default, it will look for a file
	 * named <code>[plugin name].git.properties</code> (where plugin name is the
	 * name of the plugin as returned by {@link #getName()} but all lowercase and
	 * with spaces replaced with underscores), in the <code>gitprops/</code>
	 * subdirectory of the resource classpath. If none is found, the returned Mono
	 * will complete empty.
	 * 
	 * @return a Mono emitting the git properties if found
	 */
	public Mono<Properties> getGitProperties() {
		return Mono.fromCallable(() -> {
			var props = new Properties();
			try (var stream = ClassLoader.getSystemResourceAsStream(
					"META-INF/git/" + getName().toLowerCase().replace(' ', '_') + ".git.properties")) {
				if (stream != null) {
					props.load(stream);
				}
			}
			return props;
		}).subscribeOn(Schedulers.boundedElastic());
	}
	
	/**
	 * Creates a new plugin builder with the specified name.
	 * 
	 * @param name the name of the plugin to build
	 * @return a new Builder
	 */
	public static Builder builder(String name) {
		return new Builder(name);
	}
	
	public static class Builder {
		
		private final String name;
		private final Set<Class<? extends GuildConfigDao<?>>> guildConfigExtensions = new HashSet<>();
		private CommandProvider commandProvider = new CommandProvider();
		private Supplier<? extends Mono<Void>> onReady = () -> Mono.empty();
		
		private Builder(String name) {
			this.name = requireNonNull(name);
		}
		
		/**
		 * Adds a database extension class useful to retrieve configuration for a guild.
		 * 
		 * @param extensionClass the class of the extension
		 * @return this builder
		 */
		public Builder addGuildConfigExtension(Class<? extends GuildConfigDao<?>> extensionClass) {
			requireNonNull(extensionClass);
			guildConfigExtensions.add(extensionClass);
			return this;
		}
		
		/**
		 * Sets the command provider for this plugin.
		 * 
		 * @param commandProvider the command provider
		 * @return this builder
		 */
		public Builder setCommandProvider(CommandProvider commandProvider) {
			this.commandProvider = requireNonNull(commandProvider);
			return this;
		}
		
		/**
		 * Sets a callback to invoke when the bot is ready. The Mono generated by the
		 * supplier is subscribed to when the bot is ready.
		 * 
		 * @param onReady the callback to run on ready
		 * @return this builder
		 */
		public Builder onReady(Supplier<? extends Mono<Void>> onReady) {
			this.onReady = requireNonNull(onReady);
			return this;
		}
		
		/**
		 * Builds the plugin instance.
		 * 
		 * @return the plugin instance
		 */
		public Plugin build() {
			return new Plugin(name, guildConfigExtensions, commandProvider, onReady);
		}
	}
}
