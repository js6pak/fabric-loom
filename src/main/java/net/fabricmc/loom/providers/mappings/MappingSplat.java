/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019 Chocohead
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.fabricmc.loom.providers.mappings;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.fabricmc.stitch.util.Pair;

import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Field;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Method;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.ArgOnlyMethod;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.CombinedField;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.CombinedMethod;

public class MappingSplat implements Iterable<CombinedMapping> {
	public static class CombinedMapping {
		public static class CombinedField {
			public final String from, fallback, to;
			public final String fromDesc, fallbackDesc, toDesc;

			public CombinedField(String from, String fromDesc, String fallback, String fallbackDesc, String to, String toDesc) {
				this.from = from;
				this.fromDesc = fromDesc;
				this.fallback = fallback;
				this.fallbackDesc = fallbackDesc;
				this.to = to;
				this.toDesc = toDesc;
			}
		}
		public static class CombinedMethod extends CombinedField {
			private final String[] args;

			public CombinedMethod(String from, String fromDesc, String fallback, String fallbackDesc, String to, String toDesc, String[] args) {
				super(from, fromDesc, fallback, fallbackDesc, to, toDesc);

				this.args = args != null ? args : new String[0];
			}

			public boolean hasArgs() {
				return args.length > 0;
			}

			public String arg(int index) {
				return args.length > index ? args[index] : null;
			}

			public Iterable<String> namedArgs() {
				if (!hasArgs()) {
					return () -> Collections.emptyIterator();
				} else {
					return () -> new Iterator<String>() {
						private int head = args.length - 1;

						@Override
						public boolean hasNext() {
							return head >= 0;
						}

						@Override
						public String next() {
							String next = head + ": " + args[head--];
							while (hasNext() && args[head] == null) head--;
							return next;
						}
					};
				}
			}
		}
		public static class ArgOnlyMethod {
			public final String from, fromDesc;
			private final String[] args;

			ArgOnlyMethod(CombinedMethod method) {
				this(method.from, method.fromDesc, method.args);
			}

			public ArgOnlyMethod(String from, String fromDesc, String[] args) {
				this.from = from;
				this.fromDesc = fromDesc;
				this.args = args;
			}

			public String arg(int index) {
				return args.length > index ? args[index] : null;
			}

			public Iterable<String> namedArgs() {
				return () -> new Iterator<String>() {
					private int head = args.length - 1;

					@Override
					public boolean hasNext() {
						return head >= 0;
					}

					@Override
					public String next() {
						String next = head + ": " + args[head--];
						while (hasNext() && args[head] == null) head--;
						return next;
					}
				};
			}
		}

		public final String from, fallback, to;
		final Map<String, CombinedMethod> methods = new HashMap<>();
		final Map<String, ArgOnlyMethod> bonusArgs = new HashMap<>();
		final Map<String, CombinedField> fields = new HashMap<>();

		public CombinedMapping(String from, String fallback, String to) {
			this.from = from;
			this.fallback = fallback;
			this.to = to;
		}

		public Iterable<CombinedMethod> methods() {
			return methods.values();
		}

		public Iterable<ArgOnlyMethod> bonusArgs() {
			return bonusArgs.values();
		}

		public Iterable<ArgOnlyMethod> allArgs() {
			Set<ArgOnlyMethod> args = new HashSet<>(bonusArgs.values());
			methods.values().stream().filter(CombinedMethod::hasArgs).map(ArgOnlyMethod::new).forEach(args::add);
			return Collections.unmodifiableSet(args);
		}

		public Iterable<CombinedField> fields() {
			return fields.values();
		}
	}

	private final Map<String, CombinedMapping> mappings = new HashMap<>();

	public MappingSplat(MappingBlob mappings, MappingBlob fallback) {
		UnaryOperator<String> fallbackRemapper = className -> {
			String triedMapping = fallback.tryMapName(className);
			return triedMapping != null ? triedMapping : className;
		};
		UnaryOperator<String> remapper = className -> {
			String triedMapping = mappings.tryMapName(className);
			return triedMapping != null ? triedMapping : fallbackRemapper.apply(className);
		};

		//Fallback should cover all of mapping's class names, with the potential for more it doesn't have
		for (Mapping mapping : fallback) {
			String notch = mapping.from;
			Mapping other = mappings.get(notch);

			String inter = either(mapping.to, notch);
			String name = findName(other.to, inter, notch, mappings);
			assert !inter.equals(notch) || name.equals(notch);

			CombinedMapping combined = new CombinedMapping(notch, inter, name);
			this.mappings.put(notch, combined);

			for (Method method : mapping.methods()) {
				Method otherMethod = other.method(method);
				notch = method.fromName;

				if (notch.charAt(0) == '<') {
					name = inter = notch;
				} else {
					inter = method.nameOr(notch);
					name = otherMethod.nameOr(inter);
				}
				String interDesc = makeDesc(method, fallbackRemapper);
				String nameDesc = makeDesc(otherMethod, remapper);
				String[] args = either(otherMethod.args(), method.args());

				CombinedMethod combinedMethod = new CombinedMethod(notch, method.fromDesc, inter, interDesc, name, nameDesc, args);
				combined.methods.put(notch + method.fromDesc, combinedMethod);
			}

			for (Field field : mapping.fields()) {
				Field otherField = other.field(field);
				notch = field.fromName;

				inter = field.nameOr(notch);
				String interDesc = makeDesc(field, fallbackRemapper);
				name = otherField.nameOr(inter);
				String nameDesc = makeDesc(otherField, remapper);

				CombinedField combinedField = new CombinedField(notch, field.fromDesc, inter, interDesc, name, nameDesc);
				combined.fields.put(notch + ";;" + field.fromDesc, combinedField);
			}
		}

		//Sometimes Yarn versions include their own mappings without Intermediary backing (which is bad really)
		Map<String, Pair<String, Map<String, String>>> yarnOnlyMappings = new HashMap<>();

		for (Mapping mapping : mappings) {
			String notch = mapping.from;

			Mapping other = fallback.get(notch);
			CombinedMapping combined = this.mappings.get(notch);
			if (other == null || combined == null) {
				System.err.println("Warning: Missing " + (other == null ? combined == null ? "both" : "from mappings" : "from combined") + ": " + notch + " (" + mapping.to + ")");
				continue;
			}

			for (Method method : mapping.methods()) {
				if (other.hasMethod(method)) continue;
				notch = method.fromName;

				if (notch.charAt(0) == '<') {
					//Args for constructors (and static blocks) won't appear in fallback from intermediary mappings not assigning constructor names
					String interDesc = remapDesc(method.fromDesc, fallbackRemapper);
					String nameDesc = makeDesc(method, remapper);

					CombinedMethod combinedMethod = new CombinedMethod(notch, method.fromDesc, notch, interDesc, notch, nameDesc, method.args());
					combined.methods.put(notch + method.fromDesc, combinedMethod);
				} else {
					if (!notch.equals(method.nameOr(notch))) {
						//Changing Notch names without intermediaries to back it up is not cross-version safe and shouldn't be done
						//throw new IllegalStateException("Extra mappings missing from fallback! Unable to find " + mapping.from + '#' + method.fromName + method.fromDesc + " (" + mapping.to + '#' + method.name() + ')');

						//Yarn sometimes does however, so we'll just the cases where it does and not use them
						yarnOnlyMappings.computeIfAbsent(mapping.from, k -> Pair.of(mapping.to, new HashMap<>())).getRight().put(method.fromName + method.fromDesc, method.name());
					}

					if (method.hasArgs()) {
						ArgOnlyMethod bonusMethod = new ArgOnlyMethod(notch, method.fromDesc, method.args());
						combined.bonusArgs.put(notch + method.fromDesc, bonusMethod);
					}
				}
			}

			for (Field field : mapping.fields()) {
				if (other.hasField(field)) continue;

				yarnOnlyMappings.computeIfAbsent(mapping.from, k -> Pair.of(mapping.to, new HashMap<>())).getRight().put(field.fromDesc + ' ' + field.fromName, field.name());
				//throw new IllegalStateException("Extra mapping missing from fallback! Unable to find " + mapping.from + '#' + field.fromName + " (" + field.fromDesc + ')');
			}
		}

		if (!yarnOnlyMappings.isEmpty()) {//We should crash from this, but that's a nuisance as Yarn has to get fixed
			System.out.println("Invalid Yarn mappings (ie missing Intermediaries) found:");

			for (Entry<String, Pair<String, Map<String, String>>> entry : yarnOnlyMappings.entrySet()) {
				String notch = entry.getKey();
				String yarn = entry.getValue().getLeft();
				System.out.println("\tIn " + notch + " (" + yarn + ')');

				//Split the methods apart from the fields
				Map<Boolean, List<Entry<String, String>>> extras = entry.getValue().getRight().entrySet().stream().collect(Collectors.partitioningBy(extra -> extra.getKey().contains("(")));

				printExtras(extras.get(Boolean.TRUE), "methods");
				printExtras(extras.get(Boolean.FALSE), "fields");

				System.out.println();
			}
		}
	}

	private static void printExtras(List<Entry<String, String>> extras, String type) {
		if (!extras.isEmpty()) {
			System.out.println("\t\tExtra mapped " + type + ':');

			for (Entry<String, String> extra : extras) {
				System.out.println("\t\t\t" + extra.getKey() + " => " + extra.getValue());
			}
		}
	}

	@Override
	public Iterator<CombinedMapping> iterator() {
		return mappings.values().iterator();
	}

	private static <T> T either(T prefered, T other) {
		return prefered != null ? prefered : other;
	}

	private static String findName(String name, String inter, String notch, MappingBlob mappings) {
		if (name != null) {
			return name;
		} else {
			String[] segments = notch.split("\\$");

			if (segments.length > 1) {
				for (int end = segments.length - 1, depth = 1; end > 0; end--, depth++) {
					StringJoiner nameBits = new StringJoiner("$");
					for (int i = 0; i < end; i++) {
						nameBits.add(segments[i]);
					}

					Mapping parent = mappings.get(nameBits.toString());
					if (parent.to != null) {
						String[] extra = inter.split("\\$");

						nameBits = new StringJoiner("$");
						nameBits.add(parent.to);
						for (int extraEnd = extra.length, i = extraEnd - depth; i < extraEnd; i++) {
							nameBits.add(extra[i]);
						}
						return nameBits.toString();
					}
				}
			}

			return inter;
		}
	}

	private static String makeDesc(Field method, UnaryOperator<String> remapper) {
		if (method.desc() != null) {
			return method.desc();
		} else {
			return remapDesc(method.fromDesc, remapper);
		}
	}

	private static final Pattern CLASS_FINDER = Pattern.compile("L([^;]+);");
	public static String remapDesc(String desc, UnaryOperator<String> classRemapper) {
		StringBuffer buf = new StringBuffer();

		Matcher matcher = CLASS_FINDER.matcher(desc);
		while (matcher.find()) {
			matcher.appendReplacement(buf, Matcher.quoteReplacement('L' + classRemapper.apply(matcher.group(1)) + ';'));
		}
		matcher.appendTail(buf);

		return buf.toString();
	}
}
