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
import java.io.IOException;
import java.net.URL;

import org.zeroturnaround.zip.ZipUtil;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.GradleSupport;
import net.fabricmc.loom.util.MinecraftVersionInfo;

public class MinecraftNativesProvider {
	public static void provide(MinecraftProvider minecraftProvider, Project project) throws IOException {
		if (!GradleSupport.extractNatives(project)) return; //No need to do this
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MinecraftVersionInfo versionInfo = minecraftProvider.versionInfo;

		File nativesDir = extension.getNativesDirectory();
		File jarStore = extension.getNativesJarStore();

		for (MinecraftVersionInfo.Library library : versionInfo.libraries) {
			File libJarFile = library.getFile(jarStore);

			if (library.allowed() && library.isNative() && libJarFile != null) {
				DownloadUtil.downloadIfChanged(new URL(library.getURL()), libJarFile, project.getLogger());

				//TODO possibly find a way to prevent needing to re-extract after each run, doesnt seem too slow
				ZipUtil.unpack(libJarFile, nativesDir);
			}
		}
	}
}
