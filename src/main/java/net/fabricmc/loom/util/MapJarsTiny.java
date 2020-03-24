/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
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

package net.fabricmc.loom.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import net.fabricmc.tinyremapper.NonClassCopyMode;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MinecraftMappedProvider;
import net.fabricmc.loom.providers.MinecraftProvider;
import net.fabricmc.loom.providers.mappings.MappingSplat;
import net.fabricmc.loom.util.AccessTransformerHelper.ZipEntryAT;
import net.fabricmc.mappings.ClassEntry;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MethodEntry;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public class MapJarsTiny {
	public void mapJars(MinecraftProvider jarProvider, MinecraftMappedProvider mapProvider, Project project) throws IOException {
		String fromM = "official";

		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		Path[] classpath = mapProvider.getMapperPaths().stream().map(File::toPath).toArray(Path[]::new);

		Path input = jarProvider.getJar().toPath();
		Path outputMapped = mapProvider.getMappedJar().toPath();
		Path outputIntermediary = mapProvider.getIntermediaryJar().toPath();

		for (String toM : Arrays.asList("named", "intermediary")) {
			Path output = "named".equals(toM) ? outputMapped : outputIntermediary;

			project.getLogger().lifecycle(":remapping minecraft (TinyRemapper, " + fromM + " -> " + toM + ")");

			TinyRemapper remapper = TinyRemapper.newRemapper()
					.withMappings(mappingsProvider.mcRemappingFactory.create(fromM, toM))
					.ignoreConflicts(extension.shouldBulldozeMappings())
					.renameInvalidLocals(true)
					.rebuildSourceFilenames(true)
					.build();

			try (OutputConsumerPath outputConsumer = new OutputConsumerPath(output)) {
				outputConsumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, null);
				remapper.readClassPath(classpath);
				remapper.readInputs(input);
				remapper.apply(outputConsumer);
			} catch (Exception e) {
				throw new RuntimeException("Failed to remap JAR " + input + " with mappings from " + mappingsProvider.MAPPINGS_TINY, e);
			} finally {
				remapper.finish();
			}
		}
	}

	public static void transform(Project project, Set<Pair<String, String>> ats, MinecraftMappedProvider jarProvider, MappingsProvider mappingProvider) throws IOException {
		project.getLogger().info("Reading in mappings...");

		Mappings mappings;
		try (InputStream in = new FileInputStream(mappingProvider.MAPPINGS_TINY)) {
			mappings = net.fabricmc.mappings.MappingsProvider.readTinyMappings(in, false);
		}

		project.getLogger().info("Read in " + mappings.getClassEntries().size() + " classes");
		project.getLogger().info("Working out what we have to do");

		final String wildcard = "<*>"; //Special marker for the class itself rather than a method
		Map<String, Set<String>> transforms = new HashMap<>();
		Map<String, Set<String>> interTransforms = new HashMap<>();

		Map<Boolean, Set<Pair<String, String>>> bits = ats.stream().collect(Collectors.partitioningBy(pair -> pair.getRight() != null, Collectors.toSet()));
		Set<String> rawClasses = bits.get(Boolean.FALSE).stream().map(Pair::getLeft).collect(Collectors.toSet());

		for (ClassEntry entry : mappings.getClassEntries()) {
			String named = entry.get("named");
			if (rawClasses.contains(named)) {
				rawClasses.remove(named);

				String inter = entry.get("intermediary");
				if (inter == null) throw new IllegalStateException("Missing intermediary name for " + named);
				transforms.computeIfAbsent(named, k -> new HashSet<>()).add(wildcard);
				interTransforms.computeIfAbsent(inter, k -> new HashSet<>()).add(wildcard);
			}
		}

		Map<String, Set<String>> methods = bits.get(Boolean.TRUE).stream().collect(Collectors.groupingBy(Pair::getLeft, Collectors.mapping(Pair::getRight, Collectors.toSet())));
		for (MethodEntry entry : mappings.getMethodEntries()) {
			EntryTriple named = entry.get("named");
			Set<String> targets = methods.get(named.getOwner());

			if (targets != null && targets.contains(named.getName() + named.getDesc())) {
				EntryTriple inter = entry.get("intermediary");
				if (inter == null) throw new IllegalStateException("Missing intermediary name for " + named);
				transforms.computeIfAbsent(named.getOwner(), k -> new HashSet<>()).add(named.getName() + named.getDesc());
				interTransforms.computeIfAbsent(inter.getOwner(), k -> new HashSet<>()).add(inter.getName() + inter.getDesc());

				targets.remove(named.getName() + named.getDesc());
				if (targets.isEmpty()) methods.remove(named.getOwner());
			}
		}

		if (!methods.isEmpty()) {
			List<String> resolved = new ArrayList<>();
			UnaryOperator<String> remapper = name -> {
				for (ClassEntry classEntry : mappings.getClassEntries()) {
					if (name.equals(classEntry.get("named"))) {
						return classEntry.get("intermediary");
					}
				}

				return name;
			};

			for (Entry<String, Set<String>> entry : methods.entrySet()) {
				List<String> resolvedConstructors = new ArrayList<>();
				Set<String> unresolvedMethods = entry.getValue();

				for (String method : unresolvedMethods) {
					//Constructors aren't included as part of the mappings, but that doesn't mean that they don't need remapping
					if (method.startsWith("<init>(")) {
						resolvedConstructors.add(method);

						transforms.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).add(method);
						interTransforms.computeIfAbsent(remapper.apply(entry.getKey()), k -> new HashSet<>()).add(MappingSplat.remapDesc(method, remapper));
					}
				}

				if (!resolvedConstructors.isEmpty()) {
					unresolvedMethods.removeAll(resolvedConstructors);

					if (unresolvedMethods.isEmpty()) {
						resolved.add(entry.getKey());
					}
				}
			}

			if (!resolved.isEmpty()) resolved.forEach(methods::remove);
		}

		if (!rawClasses.isEmpty() || !methods.isEmpty()) {
			project.getLogger().error("Unable to find mappings for the following entries in access transformer:");
			rawClasses.forEach(name -> project.getLogger().error('\t' + name));
			methods.forEach((key, value) -> {
				project.getLogger().error('\t' + key + ':');
				value.forEach(name -> project.getLogger().error("\t\t" + name));
			});
			throw new InvalidUserDataException("Invalid lines found within access transformer");
		}
		project.getLogger().info("Found " + transforms.size() + " classes that need tinkering with");
		project.getLogger().lifecycle(":transforming minecraft");

		project.getLogger().info("Transforming intermediary jar");
		doTheDeed(jarProvider.MINECRAFT_INTERMEDIARY_JAR, mappings, "intermediary", interTransforms, wildcard);
		project.getLogger().info("Transforming named jar");
		doTheDeed(jarProvider.MINECRAFT_MAPPED_JAR, mappings, "named", transforms, wildcard);
		project.getLogger().info("Transformation complete"); //Probably, successful is another matter
	}

	private static void doTheDeed(File jar, Mappings mappings, String type, Map<String, Set<String>> transforms, String wildcard) throws IOException {
		Set<String> classPool = mappings.getClassEntries().parallelStream().map(entry -> entry.get(type)).collect(Collectors.toSet());
		ZipEntryAT[] transformers = AccessTransformerHelper.makeZipATs(classPool, transforms, wildcard);

		ZipUtil.transformEntries(jar, transformers);

		if (!Arrays.stream(transformers).allMatch(ZipEntryAT::didTransform)) {
			List<String> missed = new ArrayList<>();
			for (ZipEntryAT transformer : transformers) {
				if (!transformer.didTransform()) {
					String name = transformer.getPath();
					missed.add(name.substring(0, name.length() - ".class".length()));
				}
			}
			throw new IllegalStateException("Finished transforming but missed " + missed);
		}
	}
}
