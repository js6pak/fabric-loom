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

package net.fabricmc.loom.dependencies;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import com.google.common.base.Predicates;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import org.apache.commons.io.IOUtils;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.FileUtils;
import org.zeroturnaround.zip.transform.StringZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MinecraftMappedProvider;
import net.fabricmc.loom.util.AccessTransformerHelper;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public class ModProcessor {
	private static final Gson GSON = new Gson();

	public static void processMod(File input, File output, Project project, Configuration config, Optional<File> sources) throws IOException {
		if (output.exists()) {
			output.delete();
		}

		remapJar(input, output, project, sources);

		//Enable this if you want your nested jars to be extracted, this will extract **all** jars
		if (project.getExtensions().getByType(LoomGradleExtension.class).extractJars) {
			handleNestedJars(input, project, config);
		}

		//Always strip the nested jars
		stripNestedJars(output);
	}

	private static void handleNestedJars(File input, Project project, Configuration config) throws IOException {
		JarFile jarFile = new JarFile(input);
		JarEntry modJsonEntry = jarFile.getJarEntry("fabric.mod.json");

		if (modJsonEntry == null) {
			jarFile.close();
			return;
		}

		try (InputStream inputStream = jarFile.getInputStream(modJsonEntry)) {
			JsonObject json = GSON.fromJson(new InputStreamReader(inputStream), JsonObject.class);

			if (json == null || !json.has("jars")) {
				return;
			}

			JsonArray jsonArray = json.getAsJsonArray("jars");

			for (int i = 0; i < jsonArray.size(); i++) {
				JsonObject jsonObject = jsonArray.get(i).getAsJsonObject();
				String fileName = jsonObject.get("file").getAsString();
				project.getLogger().lifecycle(String.format("Found %s nested in %s", fileName, input.getName()));
				processNestedJar(jarFile, fileName, project, config);
			}
		}
	}

	private static void processNestedJar(JarFile parentJar, String fileName, Project project, Configuration config) throws IOException {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);

		JarEntry entry = parentJar.getJarEntry(fileName);

		if (entry == null) {
			throw new RuntimeException(String.format("%s was not found in %s", fileName, parentJar.getName()));
		}

		File nestedFile = new File(extension.getNestedModCache(), fileName.substring(fileName.lastIndexOf("/")));

		try (InputStream jarStream = parentJar.getInputStream(entry)) {
			FileUtils.copy(jarStream, nestedFile);
		}

		File remappedFile = new File(extension.getRemappedModCache(), fileName.substring(fileName.lastIndexOf("/")));

		//There is an expectation the outer jar doesn't contain the nested jar's sources
		//There is nothing stopping them from doing so, however this is not configured by default (so no one will)
		processMod(nestedFile, remappedFile, project, config, Optional.empty());

		if (!remappedFile.exists()) {
			throw new RuntimeException("Failed to find processed nested jar");
		}

		//Add the project right onto the remapped mods, hopefully this works
		project.getDependencies().add(config.getName(), project.files(remappedFile));
	}

	private static void stripNestedJars(File file) {
		//Strip out all contained jar info as we dont want loader to try and load the jars contained in dev.
		ZipUtil.transformEntry(file, new ZipEntryTransformerEntry("fabric.mod.json", new StringZipEntryTransformer() {
			@Override
			protected String transform(ZipEntry zipEntry, String input) throws IOException {
				JsonObject json = GSON.fromJson(input, JsonObject.class);
				json.remove("jars");
				return GSON.toJson(json);
			}
		}));
	}

	private static void remapJar(File input, File output, Project project, Optional<File> sources) throws IOException {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		String fromM = "intermediary";
		String toM = "named";

		MinecraftMappedProvider mappedProvider = extension.getMinecraftMappedProvider();
		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		Path inputPath = input.getAbsoluteFile().toPath();
		Path mc = mappedProvider.MINECRAFT_INTERMEDIARY_JAR.toPath();
		Path[] mcDeps = mappedProvider.getMapperPaths().stream()
			.map(File::toPath)
			.toArray(Path[]::new);
		Path[] modCompiles = Constants.MOD_COMPILE_ENTRIES.stream().map(RemappedConfigurationEntry::getSourceConfiguration).flatMap(sourceConfig -> {
			return project.getConfigurations().getByName(sourceConfig).getFiles().stream()
				.filter(Predicates.not(input::equals))
				.map(file -> {
					assert !file.equals(input); //Filter should have caught it
					return file.toPath();
				});
		}).distinct().toArray(Path[]::new);

		project.getLogger().lifecycle(":remapping " + input.getName() + " (TinyRemapper, " + fromM + " -> " + toM + ")");

		// If the sources don't exist, we want remapper to give nicer names to the missing variable names.
		// However, if the sources do exist, if remapper gives names to the parameters that prevents IDEs (at least IDEA)
		// from replacing the parameters with the actual names from the sources.
		boolean sourcesExist = sources.isPresent();

		TinyRemapper remapper = TinyRemapper.newRemapper()
						.withMappings(TinyRemapperMappingsHelper.create(mappingsProvider.getMappings(), fromM, toM, false))
						.ignoreConflicts(extension.shouldBulldozeMappings())
						.renameInvalidLocals(!sourcesExist)
						.build();

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath(Paths.get(output.getAbsolutePath()))) {
			outputConsumer.addNonClassFiles(inputPath, NonClassCopyMode.FIX_META_INF, null);
			remapper.readClassPath(modCompiles);
			remapper.readClassPath(mc);
			remapper.readClassPath(mcDeps);
			remapper.readInputs(inputPath);
			remapper.apply(outputConsumer);
		} finally {
			remapper.finish();
		}

		if (!output.exists()) {
			throw new RuntimeException("Failed to remap JAR to " + toM + " file not found: " + output.getAbsolutePath());
		}

		if (AccessTransformerHelper.deobfATs(remapper, output)) {
			project.getLogger().info("Found and remapped access transformer in " + input.getName());
		}
	}

	static JsonObject readInstallerJson(File file, Project project) {
		try {
			LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
			String launchMethod = extension.getLoaderLaunchMethod();

			String jsonStr;
			int priority = 0;

			try (JarFile jarFile = new JarFile(file)) {
				ZipEntry entry = null;

				if (!launchMethod.isEmpty()) {
					entry = jarFile.getEntry("fabric-installer." + launchMethod + ".json");

					if (entry == null) {
						project.getLogger().warn("Could not find loader launch method '" + launchMethod + "', falling back");
					}
				}

				if (entry == null) {
					entry = jarFile.getEntry("fabric-installer.json");
					priority++;

					if (entry == null) {
						return null;
					}
				}

				try (InputStream inputstream = jarFile.getInputStream(entry)) {
					jsonStr = IOUtils.toString(inputstream, StandardCharsets.UTF_8);
				}
			}

			JsonObject jsonObject = GSON.fromJson(jsonStr, JsonObject.class);
			return jsonObject;
		} catch (IOException e) {
			e.printStackTrace();
		}

		return null;
	}
}
