package com.github.alex1304.ultimategdbot.api.command;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.alex1304.ultimategdbot.api.command.annotation.CommandAction;
import com.github.alex1304.ultimategdbot.api.command.annotation.CommandSpec;
import com.github.alex1304.ultimategdbot.api.command.annotation.Subcommand;
import com.github.alex1304.ultimategdbot.api.command.parser.Parser;
import com.github.alex1304.ultimategdbot.api.utils.Markdown;

import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple4;
import reactor.util.function.Tuples;

/**
 * Command implemented via annotations.
 */
public class AnnotatedCommand implements Command {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AnnotatedCommand.class);
	
	private final Object obj;
	private final Function<Context, Mono<Void>> action;
	private final Set<String> aliases;
	private final PermissionLevel permLevel;
	private final Scope scope;
	
	private AnnotatedCommand(Object obj, Function<Context, Mono<Void>> action, Set<String> aliases, PermissionLevel permLevel,
			Scope scope) {
		this.obj = obj;
		this.action = action;
		this.aliases = aliases;
		this.permLevel = permLevel;
		this.scope = scope;
	}

	@Override
	public Mono<Void> run(Context ctx) {
		return action.apply(ctx);
	}

	@Override
	public Set<String> getAliases() {
		return aliases;
	}
	
	@Override
	public PermissionLevel getPermissionLevel() {
		return permLevel;
	}
	
	@Override
	public Scope getScope() {
		return scope;
	}
	
	@Override
	public String toString() {
		return "AnnotatedCommand{obj=" + obj.toString() + "}";
	}
	
	static AnnotatedCommand buildFromAnnotatedObject(Object obj) {
		var cmdSpecAnnot = obj.getClass().getAnnotation(CommandSpec.class);
		if (cmdSpecAnnot == null) {
			throw new InvalidAnnotatedObjectException("@CommandSpec annotation is missing");
		}
		if (cmdSpecAnnot.aliases().length == 0) {
			throw new InvalidAnnotatedObjectException("@CommandSpec does not define any alias for the command");
		}
		var cmdActions = new ArrayList<Tuple4<String, Class<? extends Parser<?>>[], Parser<?>[], Method>>();
		for (var method : obj.getClass().getMethods()) {
			method.setAccessible(true);
			var cmdActionAnnot = method.getAnnotation(CommandAction.class);
			if (cmdActionAnnot != null) {
				var subcmdAnnot = method.getAnnotation(Subcommand.class);
				var subcmdAliases = subcmdAnnot == null || subcmdAnnot.value().length == 0 ? new String[] { "" } : subcmdAnnot.value();
				var parserInstances = instantiateArgParsers(cmdActionAnnot.value());
				for (var alias : subcmdAliases) {
					if (!cmdActions.add(Tuples.of(alias, cmdActionAnnot.value(), parserInstances, method))) {
						throw new InvalidAnnotatedObjectException("Two or more methods are annotated with conflicting argument "
								+ "types and/or conflicting subcommand aliases");
					}
				}
				if (method.getReturnType() != Mono.class) {
					throw new InvalidAnnotatedObjectException("The return type of a command action method must be " + Mono.class.getName());
				}
				var paramTypes = method.getParameterTypes();
				if (paramTypes.length == 0 || paramTypes[0] != Context.class) {
					throw new InvalidAnnotatedObjectException("The first parameter of a command action method must be of type "
							+ Context.class.getName());
				}
				paramTypes = Arrays.copyOfRange(paramTypes, 1, paramTypes.length);
				if (paramTypes.length != parserInstances.length) {
					throw new InvalidAnnotatedObjectException("Number of parameters of the command action method differs with the number "
							+ "of argument parsers given via @CommandAction");
				}
				var parserTypes = new Class<?>[parserInstances.length];
				Arrays.setAll(parserTypes, i -> parserInstances[i].type());
				for (int i = 0 ; i < parserTypes.length ; i++) {
					if (!isCompatible(paramTypes[i], parserTypes[i])) {
						throw new InvalidAnnotatedObjectException("Argument " + (i + 1) + " of " 
								+ method.getDeclaringClass().getName() + "#" + method.getName() + " (" + paramTypes[i] + ")"
								+ " is incompatible with the expected type " + parserTypes[i]);
					}
				}
			}
		}
		if (cmdActions.isEmpty()) {
			throw new InvalidAnnotatedObjectException("At least one method must define an action for the command via @CommandAction");
		}
		return new AnnotatedCommand(obj,
				ctx -> {
					var args = ctx.getArgs();
					var firstArgIndex = new AtomicInteger(1);
					var foundMatchingAction = new AtomicBoolean();
					var expectedArguments = Collections.synchronizedList(new ArrayList<String>());
					var argumentParseException = new AtomicReference<Tuple2<Long, ArgumentParseException>>();
					var isSubcommandUsed = args.tokenCount() > 1 && cmdActions.stream().anyMatch(action -> action.getT1().equalsIgnoreCase(args.get(1)));
					return Flux.fromIterable(cmdActions)
							.filter(TupleUtils.predicate((subcmdAlias, parserClasses, parserInstances, method) -> isSubcommandUsed 
									? subcmdAlias.equalsIgnoreCase(args.get(1)) : subcmdAlias.isEmpty()))
							.doOnNext(__ -> firstArgIndex.set(isSubcommandUsed ? 2 : 1))
							.concatMap(TupleUtils.function((subcmdAlias, parserClasses, parserInstances, method) -> {
								if (foundMatchingAction.get()) {
									LOGGER.debug("Skipping method {}#{}: another matching method was already found.", method
											.getDeclaringClass().getName(), method.getName());
									return Mono.empty();
								}
								var actualTokenCount = args.tokenCount() - firstArgIndex.get();
								if (actualTokenCount < method.getParameters().length - 1) {
									for (var i = actualTokenCount + 1 ; i < method.getParameters().length ; i++) {
										expectedArguments.add(method.getParameters()[i].getName());
									}
									LOGGER.debug("Skipping method {}#{}: Missing arguments.", method
											.getDeclaringClass().getName(), method.getName());
									return Mono.empty();
								}
								var mergedArgs = args.getTokens(method.getParameters().length - 1 + firstArgIndex.get());
								mergedArgs = mergedArgs.subList(firstArgIndex.get(), mergedArgs.size());
								//LOGGER.debug("Executing method {}#{}", method.getDeclaringClass().getName(), method.getName());
								return Flux.zip(Flux.fromArray(parserInstances), Flux.fromIterable(mergedArgs))
										.index()
										.flatMapSequential(TupleUtils.function((i, parserWithArg) -> parserWithArg.getT1().parse(ctx, parserWithArg.getT2())
												.onErrorResume(ArgumentParseException.class, e -> Mono.fromRunnable(() -> argumentParseException
														.compareAndSet(null, Tuples.of(i + 1, e))))))
										.collectList()
										.defaultIfEmpty(List.of())
										.filter(argList -> argList.size() == parserInstances.length)
										.switchIfEmpty(Mono.fromRunnable(() -> 
												LOGGER.debug("Skipping method {}#{}: cannot parse one of the arguments.", method
														.getDeclaringClass().getName(), method.getName())))
										.map(argList -> new ArrayList<Object>(argList))
										.doOnNext(argList -> argList.add(0, ctx))
										.flatMap(argList -> Mono.fromCallable(() -> method.invoke(obj, argList.toArray())))
										.cast(Mono.class)
										.flatMap(mono -> (Mono<?>) mono)
										.doOnTerminate(() -> LOGGER.debug("Successfully executed method {}#{}", method
														.getDeclaringClass().getName(), method.getName()));
							}))
							.then(Mono.fromCallable(() -> {
								if (!foundMatchingAction.get()) {
									if (!expectedArguments.isEmpty()) {
										throw new CommandFailedException("Missing arguments: " + expectedArguments.stream()
												.map(Markdown::code)
												.collect(Collectors.joining(", ")));
									} else if (argumentParseException.get() != null) {
										throw new CommandFailedException("Failed to parse argument " + argumentParseException.get().getT1() + ":"
												+ argumentParseException.get().getT2().getMessage());
									}
								}
								return null;
							}))
							.then();
				},
				Set.of(cmdSpecAnnot.aliases()),
				cmdSpecAnnot.permLevel(),
				cmdSpecAnnot.scope());
	}

	private static Parser<?>[] instantiateArgParsers(Class<? extends Parser<?>>[] parserTypes)  {
		var parserInstances = new Parser<?>[parserTypes.length];
		for (var i = 0 ; i < parserInstances.length ; i++) {
			try {
				parserInstances[i] = parserTypes[i].getConstructor().newInstance();
			} catch (Exception e) {
				LOGGER.error("An error occured when instantating an argument parser", e);
				throw Exceptions.propagate(e);
			}
		}
		return parserInstances;
	}
	
	private static boolean isCompatible(Class<?> paramType, Class<?> parserType) {
		if (paramType.isAssignableFrom(parserType)) {
			return true;
		}
		if (paramType == int.class || paramType == Integer.class) {
			return parserType == int.class || parserType == Integer.class;
		} else if (paramType == long.class || paramType == Long.class) {
			return parserType == long.class || parserType == Long.class;
		} else if (paramType == float.class || paramType == Float.class) {
			return parserType == float.class || parserType == Float.class;
		} else if (paramType == double.class || paramType == Double.class) {
			return parserType == double.class || parserType == Double.class;
		} else if (paramType == char.class || paramType == Character.class) {
			return parserType == char.class || parserType == Character.class;
		} else if (paramType == boolean.class || paramType == Boolean.class) {
			return parserType == boolean.class || parserType == Boolean.class;
		}
		return false;
	}
}
