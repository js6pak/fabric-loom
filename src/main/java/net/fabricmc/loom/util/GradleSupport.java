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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.gradle.api.Project;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;

/** This is used to bridge the gap over large Gradle API changes. */
public class GradleSupport {
	public static int majorGradleVersion(Project project) {
		String version = project.getGradle().getGradleVersion();

		int split = version.indexOf('.');
		assert split > 0: "Weird Gradle version: " + version;

		return Integer.parseUnsignedInt(version.substring(0, split));
	}

	public static int minorGradleVersion(Project project) {
		String version = project.getGradle().getGradleVersion();

		int split = version.indexOf('.') + 1;
		assert split > 1: "Weird Gradle version: " + version;

		int splitSquared = version.indexOf('.', split);
		if (splitSquared <= split) splitSquared = version.indexOf('-', split);
		assert splitSquared > split: "Weird Gradle version: " + version;

		return Integer.parseUnsignedInt(version.substring(split, splitSquared));
	}

	public static int patchGradleVersion(Project project) {
		String version = project.getGradle().getGradleVersion();

		int split = version.indexOf('.') + 1;
		assert split > 1: "Weird Gradle version: " + version;

		int splitSquared = version.indexOf('.', split);
		if (splitSquared <= split) return 0;

		int splitCubed= version.indexOf('.', ++splitSquared);
		if (splitCubed <= splitSquared) splitCubed = version.indexOf('-', splitSquared);
		if (splitCubed <= splitSquared) return 0; //Nothing says Gradle must have a patch version

		return Integer.parseUnsignedInt(version.substring(splitSquared, splitCubed));
	}

	public static boolean extractNatives(Project project) {
		int major = majorGradleVersion(project);
		return major > 5 || major == 5 && minorGradleVersion(project) >= 6 && patchGradleVersion(project) >= 3;
	}

	public static RegularFileProperty getFileProperty(Project project) {
		try {
//			return project.getObjects().fileProperty();
			Method method = ObjectFactory.class.getMethod("fileProperty");
			assert method.isAccessible();
			return (RegularFileProperty) method.invoke(project.getObjects());
		} catch (NoSuchMethodError | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			assert majorGradleVersion(project) < 5;
		}

		try {
			Method method = ProjectLayout.class.getMethod("fileProperty");
			assert method.isAccessible();
			return (RegularFileProperty) method.invoke(project.getLayout());
		} catch (ReflectiveOperationException | ClassCastException e) {
			throw new IllegalStateException("Can't find new or old fileProperty?", e);
		}
	}
}