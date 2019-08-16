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

package net.fabricmc.loom.providers;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.mappings.EnigmaReader;
import net.fabricmc.loom.providers.mappings.MappingBlob;
import net.fabricmc.loom.providers.mappings.MappingSplat;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.ArgOnlyMethod;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.CombinedField;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.CombinedMethod;
import net.fabricmc.loom.providers.mappings.TinyReader;
import net.fabricmc.loom.providers.mappings.TinyWriter;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyProvider;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.loom.util.Version;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.tinyremapper.IMappingProvider;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.gradle.api.Project;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

//TODO fix local mappings
//TODO possibly use maven for mappings, can fix above at the same time
public class MappingsProvider extends DependencyProvider {
	public interface MappingFactory {//IOException throwing BiPredicate<String, String, IMappingProvider>
		IMappingProvider create(String fromMapping, String toMapping) throws IOException;
	}
	public MinecraftMappedProvider mappedProvider;
	public MappingFactory mcRemappingFactory;

	public String mappingsName;
	public String minecraftVersion;
	public String mappingsVersion;

	private File MAPPINGS_DIR;
	private File MAPPINGS_TINY_BASE;
	public File MAPPINGS_TINY;
	private File intermediaryNames;
	private File parameterNames;

	public File MAPPINGS_MIXIN_EXPORT;

	private SoftReference<Mappings> mappings;

	public Mappings getMappings() throws IOException {
		if (mappings == null || mappings.get() == null) {
			try (FileInputStream stream = new FileInputStream(MAPPINGS_TINY)) {
				mappings = new SoftReference<>(
						net.fabricmc.mappings.MappingsProvider.readTinyMappings(stream, false)
				);
			}
		}

		return mappings.get();
	}

	@Override
	public void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProvider minecraftProvider = getDependencyManager().getProvider(MinecraftProvider.class);

		project.getLogger().lifecycle(":setting up mappings (" + dependency.getDependency().getName() + " " + dependency.getResolvedVersion() + ")");

		String version = extension.mappingsVersion != null ? extension.mappingsVersion : dependency.getResolvedVersion();
		File mappingsJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find dependency " + dependency));

		this.mappingsName = dependency.getDependency().getName();

		Version mappingsVersion = new Version(version);
		this.minecraftVersion = mappingsVersion.getMinecraftVersion();
		this.mappingsVersion = mappingsVersion.getMappingsVersion();

		initFiles(project);

		if (!MAPPINGS_DIR.exists()) {
			MAPPINGS_DIR.mkdir();
		}

		if (!MAPPINGS_TINY_BASE.exists() || !MAPPINGS_TINY.exists()) {
			if (!MAPPINGS_TINY_BASE.exists()) {
				switch (FilenameUtils.getExtension(mappingsJar.getName())) {
				case "zip": {//Directly downloaded the enigma file (:enigma@zip)
					if (parameterNames.exists()) parameterNames.delete();

					project.getLogger().lifecycle(":loading " + intermediaryNames.getName());
					MappingBlob tiny = new MappingBlob();
					if (!intermediaryNames.exists()) {//Grab intermediary mappings (which aren't in the enigma file)
						FileUtils.copyURLToFile(new URL("https://github.com/FabricMC/intermediary/raw/master/mappings/" + minecraftVersion.replace(" ", "%20") + ".tiny"), intermediaryNames);
					}
					TinyReader.readTiny(intermediaryNames.toPath(), tiny);

					project.getLogger().lifecycle(":loading " + mappingsJar.getName());
					MappingBlob enigma = new MappingBlob();
					EnigmaReader.readEnigma(mappingsJar.toPath(), enigma);

					project.getLogger().lifecycle(":combining mappings");
					MappingSplat combined = new MappingSplat(enigma, tiny);

					project.getLogger().lifecycle(":writing " + MAPPINGS_TINY_BASE.getName());
					try (TinyWriter writer = new TinyWriter(MAPPINGS_TINY_BASE.toPath())) {
						for (CombinedMapping mapping : combined) {
							String notch = mapping.from;
							writer.acceptClass(notch, mapping.to, mapping.fallback);

							for (CombinedMethod method : mapping.methods()) {
								writer.acceptMethod(notch, method.from, method.fromDesc, method.to, method.fallback);
							}

							for (CombinedField field : mapping.fields()) {
								writer.acceptField(notch, field.from, field.fromDesc, field.to, field.fallback);
							}
						}
					}

					project.getLogger().lifecycle(":writing " + parameterNames.getName());
					try (BufferedWriter writer = new BufferedWriter(new FileWriter(parameterNames, false))) {
						for (CombinedMapping mapping : combined) {
							for (ArgOnlyMethod method : mapping.allArgs()) {
								writer.write(mapping.to + '/' + method.from + method.fromDesc);
								writer.newLine();
								for (String arg : method.namedArgs()) {
									assert !arg.endsWith(": null"); //Skip nulls
									writer.write("\t" + arg);
									writer.newLine();
								}
							}
						}
					}
					addDependency(dependency.getDepString(), project, "runtimeOnly");
					break;
				}
				case "gz": //Directly downloaded the tiny file (:tiny@gz)
					project.getLogger().lifecycle(":extracting " + mappingsJar.getName());
					FileUtils.copyInputStreamToFile(new GZIPInputStream(new FileInputStream(mappingsJar)), MAPPINGS_TINY_BASE);
					addDependency(dependency.getDepString(), project, "runtimeOnly");
					break;

				case "jar": //Downloaded a jar containing the tiny jar
					project.getLogger().lifecycle(":extracting " + mappingsJar.getName());
					try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar.toPath(), null)) {
						Path fileToExtract = fileSystem.getPath("mappings/mappings.tiny");
						Files.copy(fileToExtract, MAPPINGS_TINY_BASE.toPath());
					}
					break;

				default: //Not sure what we've ended up with, but it's not what we want/expect
					throw new IllegalStateException("Unexpected mappings base type: " + FilenameUtils.getExtension(mappingsJar.getName()) + "(from " + mappingsJar.getName() + ')');
				}
			}

			if (MAPPINGS_TINY.exists()) {
				MAPPINGS_TINY.delete();
			}

			project.getLogger().lifecycle(":populating field names");
			new CommandProposeFieldNames().run(new String[] {
					minecraftProvider.MINECRAFT_MERGED_JAR.getAbsolutePath(),
					MAPPINGS_TINY_BASE.getAbsolutePath(),
					MAPPINGS_TINY.getAbsolutePath()
			});
		}

		if (parameterNames.exists()) {
			//Merge the tiny mappings with parameter names
			Map<String, String[]> lines = new HashMap<>();

			try (BufferedReader reader = new BufferedReader(new FileReader(parameterNames))) {
				for (String line = reader.readLine(), current = null; line != null; line = reader.readLine()) {
					if (current == null || line.charAt(0) != '\t') {
						current = line;
					} else {
						int split = line.indexOf(':'); //\tno: name
						int number = Integer.parseInt(line.substring(1, split));
						String name = line.substring(split + 2);

						String[] lineSet = lines.get(current);
						if (lineSet == null) {
							//The args are written backwards so the biggest index is first
							lines.put(current, lineSet = new String[number + 1]);
						}
						lineSet[number] = name;
					}
				}
			}

			mcRemappingFactory = (fromM, toM) -> new IMappingProvider() {
				private final IMappingProvider normal = TinyRemapperMappingsHelper.create(getMappings(), fromM, toM);

				@Override
				public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap, Map<String, String[]> localMap) {
					load(classMap, fieldMap, methodMap);
					if ("official".equals(fromM)) {
						localMap.putAll(lines);
					} else {
						//If we're not going from notch names to something else the line map is useless
						project.getLogger().warn("Missing param map from " + fromM + " to " + toM);
					}
				}

				@Override
				public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap) {
					normal.load(classMap, fieldMap, methodMap);
				}
			};
		} else {
			mcRemappingFactory = (fromM, toM) -> TinyRemapperMappingsHelper.create(getMappings(), fromM, toM);
		}

		mappedProvider = new MinecraftMappedProvider();
		mappedProvider.provide(project, extension, minecraftProvider, this, postPopulationScheduler);
	}

	public void initFiles(Project project) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MAPPINGS_DIR = new File(extension.getUserCache(), "mappings");

		MAPPINGS_TINY_BASE = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + "-" + mappingsVersion + "-base");
		MAPPINGS_TINY = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + "-" + mappingsVersion);
		intermediaryNames = new File(MAPPINGS_DIR, mappingsName + "-intermediary-" + minecraftVersion + ".tiny");
		parameterNames = new File(MAPPINGS_DIR, mappingsName + "-params-" + minecraftVersion + '-' + mappingsVersion);
		MAPPINGS_MIXIN_EXPORT = new File(extension.getProjectBuildCache(), "mixin-map-" + minecraftVersion + "-" + mappingsVersion + ".tiny");
	}

	public void clearFiles() {
		MAPPINGS_TINY.delete();
		MAPPINGS_TINY_BASE.delete();
		intermediaryNames.delete();
		parameterNames.delete();
	}

	@Override
	public String getTargetConfig() {
		return Constants.MAPPINGS;
	}
}
