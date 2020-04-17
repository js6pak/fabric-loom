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

import com.google.common.collect.Streams;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Field;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Method;

import java.util.*;
import java.util.function.ObjIntConsumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public class MappingBlob implements IMappingAcceptor, Iterable<Mapping> {
	public static class Mapping {
		public static class Method extends Field {
			private String[] args = new String[0];

			public Method(String fromName, String fromDesc) {
				super(fromName, fromDesc);

				if (fromName.charAt(0) == '<') {
					//If we're an <init> (or theoretically a <clinit>) we'll never get a name so should do it now
					setMapping(fromName, null);
				}
			}

			void addArg(String name, int index) {
				if (args.length <= index) {
					String[] longerArgs = new String[index + 1];
					System.arraycopy(args, 0, longerArgs, 0, args.length);
					args = longerArgs;
				}
				args[index] = name;
			}

			public boolean hasArgs() {
				return args.length > 0;
			}

			String[] args() {
				return args;
			}

			void args(String[] args) {
				if (this.args.length < args.length) {
					this.args = Arrays.copyOf(args, args.length);
				} else {
					System.arraycopy(args, 0, this.args, 0, args.length);
				}
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
						return head + ": " + args[head--];
					}
				};
			}

			public void iterateArgs(ObjIntConsumer<String> argConsumer) {
				for (int i = 0; i < args.length; i++) {
					if (args[i] != null) argConsumer.accept(args[i], i);
				}
			}
		}
		public static class Field {
			public final String fromName, fromDesc;
			private String toName, toDesc;

			public Field(String fromName, String fromDesc) {
				this.fromName = fromName;
				this.fromDesc = fromDesc;
			}

			void setMapping(String name, String desc) {
				this.toName = name;
				this.toDesc = desc;
			}

			public String name() {
				return toName;
			}

			public String nameOr(String alternative) {
				return toName != null ? toName : alternative;
			}

			public String desc() {
				return toDesc;
			}
		}

		public final String from;
		String to;
		final Map<String, Method> methods = new HashMap<>();
		final Map<String, Field> fields = new HashMap<>();

		public Mapping(String from) {
			this.from = from;
		}

		public String to() {
			return to;
		}

		public String toOr(String alternative) {
			return to != null ? to : alternative;
		}

		public Iterable<Method> methods() {
			return methods.values();
		}

		public boolean hasMethod(Method other) {
			return methods.containsKey(other.fromName + other.fromDesc);
		}

		public Method method(Method other) {
			return method(other.fromName, other.fromDesc);
		}

		Method method(String srcName, String srcDesc) {
			return methods.computeIfAbsent(srcName + srcDesc, k -> new Method(srcName, srcDesc));
		}

		public Iterable<Field> fields() {
			return fields.values();
		}

		public boolean hasField(Field other) {
			return fields.containsKey(other.fromName + ";;" + other.fromDesc);
		}

		public Field field(Field other) {
			return field(other.fromName, other.fromDesc);
		}

		Field field(String srcName, String srcDesc) {
			return fields.computeIfAbsent(srcName + ";;" + srcDesc, k -> new Field(srcName, srcDesc));
		}
	}

	private final Map<String, Mapping> mappings = new HashMap<>();

	public Mapping get(String srcName) {
		return mappings.computeIfAbsent(srcName, Mapping::new);
	}

	public String tryMapName(String srcName) {
		Mapping mapping = mappings.get(srcName);
		return mapping != null ? mapping.to : null;
	}

	@Override
	public void acceptClass(String srcName, String dstName) {
		get(srcName).to = dstName;
	}

	@Override
	public void acceptMethod(String srcClsName, String srcName, String srcDesc, String dstClsName, String dstName, String dstDesc) {
		get(srcClsName).method(srcName, srcDesc).setMapping(dstName, dstDesc);
	}

	@Override
	public void acceptMethodArg(String srcClsName, String srcMethodName, String srcMethodDesc, int lvIndex, String dstArgName) {
		get(srcClsName).method(srcMethodName, srcMethodDesc).addArg(dstArgName, lvIndex);
	}

	@Override
	public void acceptField(String srcClsName, String srcName, String srcDesc, String dstClsName, String dstName, String dstDesc) {
		get(srcClsName).field(srcName, srcDesc).setMapping(dstName, dstDesc);
	}

	@Override
	public Iterator<Mapping> iterator() {
		return mappings.values().iterator();
	}

	public Stream<Mapping> stream() {
		return mappings.values().stream();
	}

	public Stream<Method> streamMethods() {
		return stream().map(Mapping::methods).flatMap(Streams::stream);
	}

	public Stream<Field> streamFields() {
		return stream().map(Mapping::fields).flatMap(Streams::stream);
	}

	public enum InvertionTarget {
		FIELDS, METHODS, MEMBERS, METHOD_ARGS, ALL;
	}

	public MappingBlob invert(InvertionTarget... targets) {
		Set<InvertionTarget> aims = EnumSet.noneOf(InvertionTarget.class);
		MappingBlob invertion = new MappingBlob();

		for (InvertionTarget target : targets) {
			switch (target) {
			case METHOD_ARGS:
				aims.add(InvertionTarget.METHODS);
			case FIELDS:
			case METHODS:
				aims.add(target);
				break;

			case ALL:
				aims.add(InvertionTarget.METHOD_ARGS);
			case MEMBERS:
				aims.add(InvertionTarget.FIELDS);
				aims.add(InvertionTarget.METHODS);
				break;
			}
		}

		boolean doFields = aims.contains(InvertionTarget.FIELDS);
		boolean doMethods = aims.contains(InvertionTarget.METHODS);
		boolean doArgs = aims.contains(InvertionTarget.METHOD_ARGS);

		UnaryOperator<String> classRemapper = name -> {
			String mapping = tryMapName(name);
			return mapping != null ? mapping : name;
		};

		for (Mapping mapping : mappings.values()) {
			if (mapping.to == null) {//If there is no mapped class name there is nothing for it to invert to
				assert Streams.stream(mapping.fields()).map(Field::name).allMatch(Objects::isNull): mapping.from + " doesn't change name but has fields which do";
				assert Streams.stream(mapping.methods()).map(Method::name).allMatch(Objects::isNull): mapping.from + " doesn't change name but has methods which do";
				continue;
			}

			invertion.acceptClass(mapping.to, mapping.from);

			if (doFields) {
				for (Field field : mapping.fields()) {
					if (field.name() == null) continue;
					//assert field.desc() != null: mapping.from + '#' + field.fromName + " (" + field.fromDesc + ") changes name without a changed descriptor";

					String desc = MappingSplat.makeDesc(field, classRemapper);
					invertion.acceptField(mapping.to, field.name(), desc, mapping.from, field.fromName, field.fromDesc);
				}
			}

			if (doMethods) {
				for (Method method : mapping.methods()) {
					if (method.name() == null) continue;
					//assert method.desc() != null: mapping.from + '#' + method.fromName + method.fromDesc + " changes name without a changed descriptor";

					String desc = MappingSplat.makeDesc(method, classRemapper);
					invertion.acceptMethod(mapping.to, method.name(), desc, mapping.from, method.fromName, method.fromDesc);
					if (doArgs) invertion.get(mapping.to).method(method.name(), desc).args(method.args());
				}
			}
		}

		return invertion;
	}

	public MappingBlob rename(MappingBlob blob) {
		if (blob == null)
			return this;

		MappingBlob remap = new MappingBlob();

		UnaryOperator<String> classRemapper = name -> {
			String mapping = blob.tryMapName(name);
			return mapping != null ? mapping : name;
		};

		for (Mapping mapping : mappings.values()) {
			Mapping bridge = blob.mappings.get(mapping.from);
			boolean useBridge = bridge != null;

			String className = useBridge ? bridge.to : mapping.from;
			remap.acceptClass(className, mapping.to);

			for (Field field : mapping.fields()) {
				if (useBridge && bridge.hasField(field)) {
					Field bridged = bridge.field(field);

					if (bridged.name() != null) {
						assert bridged.desc() != null;
						remap.acceptField(className, bridged.name(), bridged.desc(), mapping.to, field.name(), field.desc());
						continue;
					}
				}

				remap.acceptField(className, field.fromName, MappingSplat.remapDesc(field.fromDesc, classRemapper), mapping.to, field.name(), field.desc());
			}

			for (Method method : mapping.methods()) {
				if (useBridge && bridge.hasMethod(method)) {
					Method bridged = bridge.method(method);

					if (bridged.name() != null) {
						assert bridged.desc() != null;
						remap.acceptMethod(className, bridged.name(), bridged.desc(), mapping.to, method.name(), method.desc());
						remap.get(className).method(bridged.name(), bridged.desc()).args(method.args());
						continue;
					}
				}

				String desc = MappingSplat.remapDesc(method.fromDesc, classRemapper);
				remap.acceptMethod(className, method.fromName, desc, mapping.to, method.name(), method.desc());
				remap.get(className).method(method.fromName, desc).args(method.args());
			}

		}

		return remap;
	}
}