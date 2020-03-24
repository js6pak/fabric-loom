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

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipError;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.AbstractPlugin;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.LoomDependencyManager;
import net.fabricmc.loom.dependencies.PhysicalDependencyProvider;
import net.fabricmc.loom.providers.openfine.Openfine;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.ManifestVersion;
import net.fabricmc.loom.util.MinecraftVersionInfo;
import net.fabricmc.loom.util.StaticPathWatcher;
import net.fabricmc.stitch.merge.JarMerger;

public class MinecraftProvider extends PhysicalDependencyProvider {
	private final Gson gson = new Gson();
	public String minecraftVersion;
	public MinecraftVersionInfo versionInfo;
	public String side;

	private File MINECRAFT_JSON;
	private File MINECRAFT_CLIENT_JAR;
	private File MINECRAFT_SERVER_JAR;
	private File MINECRAFT_MERGED_JAR;

	@Override
	public void register(LoomDependencyManager dependencyManager) {
		super.register(dependencyManager);

		dependencyManager.addProvider(new MinecraftLibraryProvider());
	}

	@Override
	public void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		this.side = extension.side;
		minecraftVersion = dependency.getDependency().getVersion();
		boolean offline = project.getGradle().getStartParameter().isOffline();

		initFiles(project);

		downloadMcJson(project, offline);

		try (FileReader reader = new FileReader(MINECRAFT_JSON)) {
			versionInfo = gson.fromJson(reader, MinecraftVersionInfo.class);
		}

		if (offline) {
			if (MINECRAFT_CLIENT_JAR.exists() && MINECRAFT_SERVER_JAR.exists()) {
				project.getLogger().debug("Found client and server jars, presuming up-to-date");
			} else if (MINECRAFT_MERGED_JAR.exists()) {
				//Strictly we don't need the split jars if the merged one exists, let's try go on
				project.getLogger().warn("Missing game jar but merged jar present, things might end badly");
			} else {
				throw new GradleException("Missing jar(s); Client: " + MINECRAFT_CLIENT_JAR.exists() + ", Server: " + MINECRAFT_SERVER_JAR.exists());
			}
		} else {
			downloadJars(project.getLogger());
		}

		if (extension.hasOptiFine()) {
			MINECRAFT_CLIENT_JAR = Openfine.process(project.getLogger(), minecraftVersion, MINECRAFT_CLIENT_JAR, MINECRAFT_SERVER_JAR, extension.getOptiFine());
			MINECRAFT_MERGED_JAR = new File(MINECRAFT_CLIENT_JAR.getParentFile(), MINECRAFT_CLIENT_JAR.getName().replace("client", "merged"));
			project.getDependencies().add(Constants.MINECRAFT_DEPENDENCIES, project.getDependencies().module("com.github.Chocohead:OptiSine:" + Openfine.VERSION));
			AbstractPlugin.addMavenRepo(project, "Jitpack", "https://jitpack.io/"); //Needed to fetch OptiSine from
		}

		if (extension.side.equals("both") && !MINECRAFT_MERGED_JAR.exists()) {
			try {
				mergeJars(project.getLogger());
			} catch (ZipError e) {
				DownloadUtil.delete(MINECRAFT_CLIENT_JAR);
				DownloadUtil.delete(MINECRAFT_SERVER_JAR);

				project.getLogger().error("Could not merge JARs! Deleting source JARs - please re-run the command and move on.", e);
				throw new RuntimeException();
			}
		}
	}

	private void initFiles(Project project) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MINECRAFT_JSON = new File(extension.getUserCache(), "minecraft-" + minecraftVersion + "-info.json");
		MINECRAFT_CLIENT_JAR = new File(extension.getUserCache(), "minecraft-" + minecraftVersion + "-client.jar");
		MINECRAFT_SERVER_JAR = new File(extension.getUserCache(), "minecraft-" + minecraftVersion + "-server.jar");
		MINECRAFT_MERGED_JAR = new File(extension.getUserCache(), "minecraft-" + minecraftVersion + "-merged.jar");
	}

	private void downloadMcJson(Project project, boolean offline) throws IOException {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		File manifests = new File(extension.getUserCache(), "version_manifest.json");

		if (offline) {
			if (manifests.exists()) {
				//If there is the manifests already we'll presume that's good enough
				project.getLogger().debug("Found version manifests, presuming up-to-date");
			} else {
				//If we don't have the manifests then there's nothing more we can do
				throw new GradleException("Version manifests not found at " + manifests.getAbsolutePath());
			}
		} else {
			if (StaticPathWatcher.INSTANCE.hasFileChanged(manifests.toPath())) {
				project.getLogger().debug("Downloading version manifests");
				DownloadUtil.downloadIfChanged(new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json"), manifests, project.getLogger());
			}
		}

		String versionManifest = Files.asCharSource(manifests, StandardCharsets.UTF_8).read();
		ManifestVersion mcManifest = new GsonBuilder().create().fromJson(versionManifest, ManifestVersion.class);

		Optional<ManifestVersion.Versions> optionalVersion = Optional.empty();

		if (extension.customManifest != null) {
			ManifestVersion.Versions customVersion = new ManifestVersion.Versions();
			customVersion.id = minecraftVersion;
			customVersion.url = extension.customManifest;
			optionalVersion = Optional.of(customVersion);
			project.getLogger().lifecycle("Using custom minecraft manifest");
		}

		if (!optionalVersion.isPresent()) {
			optionalVersion = mcManifest.versions.stream().filter(versions -> versions.id.equalsIgnoreCase(minecraftVersion)).findFirst();
		}

		if (optionalVersion.isPresent()) {
			if (offline) {
				if (MINECRAFT_JSON.exists()) {
					//If there is the manifest already we'll presume that's good enough
					project.getLogger().debug("Found Minecraft {} manifest, presuming up-to-date", minecraftVersion);
				} else {
					//If we don't have the manifests then there's nothing more we can do
					throw new GradleException("Minecraft " + minecraftVersion + " manifest not found at " + MINECRAFT_JSON.getAbsolutePath());
				}
			} else {
				if (StaticPathWatcher.INSTANCE.hasFileChanged(MINECRAFT_JSON.toPath()) || extension.customManifest != null) {
					project.getLogger().debug("Downloading Minecraft {} manifest", minecraftVersion);
					DownloadUtil.downloadIfChanged(new URL(optionalVersion.get().url), MINECRAFT_JSON, project.getLogger());
				}
			}
		} else {
			throw new RuntimeException("Failed to find minecraft version: " + minecraftVersion);
		}
	}

	private void downloadJars(Logger logger) throws IOException {
		if (versionInfo.downloads.containsKey("client")) {
			if (!MINECRAFT_CLIENT_JAR.exists() || !Checksum.equals(MINECRAFT_CLIENT_JAR, versionInfo.downloads.get("client").sha1) && StaticPathWatcher.INSTANCE.hasFileChanged(MINECRAFT_CLIENT_JAR.toPath())) {
				logger.debug("Downloading Minecraft {} client jar", minecraftVersion);
				DownloadUtil.downloadIfChanged(new URL(versionInfo.downloads.get("client").url), MINECRAFT_CLIENT_JAR, logger);
			}
		}

		if (versionInfo.downloads.containsKey("server")) {
			if (!MINECRAFT_SERVER_JAR.exists() || !Checksum.equals(MINECRAFT_SERVER_JAR, versionInfo.downloads.get("server").sha1) && StaticPathWatcher.INSTANCE.hasFileChanged(MINECRAFT_SERVER_JAR.toPath())) {
				logger.debug("Downloading Minecraft {} server jar", minecraftVersion);
				DownloadUtil.downloadIfChanged(new URL(versionInfo.downloads.get("server").url), MINECRAFT_SERVER_JAR, logger);
			}
		}
	}

	private void mergeJars(Logger logger) throws IOException {
		logger.lifecycle(":merging jars");

		try (JarMerger jarMerger = new JarMerger(MINECRAFT_CLIENT_JAR, MINECRAFT_SERVER_JAR, MINECRAFT_MERGED_JAR)) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}
	}

	@Deprecated
	public File getMappedJar() {
		return getJar();
	}

	public File getJar() {
		switch (side) {
			case "both":
				return MINECRAFT_MERGED_JAR;
			case "client":
				return MINECRAFT_CLIENT_JAR;
			case "server":
				return MINECRAFT_SERVER_JAR;
		}
		return null;
	}

	public MinecraftLibraryProvider getLibraryProvider() {
		return getProvider(MinecraftLibraryProvider.class);
	}

	@Override
	public String getTargetConfig() {
		return Constants.MINECRAFT;
	}

	@Override
	public boolean isRequired() {
		return true;
	}

	@Override
	public boolean isUnique() {
		return true;
	}
}
