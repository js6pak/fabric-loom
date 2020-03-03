/*
 * Copyright 2020 Joseph Burton
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.mcp.beta;

import net.fabricmc.loom.YarnGithubResolver;
import net.fabricmc.loom.mcp.McpConverter;
import net.fabricmc.loom.providers.mappings.TinyWriter;
import org.csveed.api.CsvClientImpl;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class BetaMcpConverter implements McpConverter {
    public void convert(File mcJar, Path mcpZip, Path tinyFile, YarnGithubResolver.MappingContainer extraMappings) throws IOException {
        Map<String, McpClass> mappings = new HashMap<>();

        try (ZipFile mcpZipFile = new ZipFile(mcpZip.toFile())) {
            ZipEntry classesCsv = mcpZipFile.getEntry("conf/classes.csv");
            List<McpClass> classes = new CsvClientImpl<>(new InputStreamReader(mcpZipFile.getInputStream(classesCsv)), McpClass.class).readBeans();

            for (McpClass $class : classes) {
                if ($class.getSide() != 0)
                    continue;

                mappings.put($class.getNotch(), $class);
            }

            ZipEntry fieldsCsv = mcpZipFile.getEntry("conf/fields.csv");
            List<McpMember> fields = new CsvClientImpl<>(new InputStreamReader(mcpZipFile.getInputStream(fieldsCsv)), McpMember.class).readBeans();

            for (McpMember field : fields) {
                if (field.getSide() != 0)
                    continue;

                McpClass mapping = mappings.get(field.getClassnotch());
                mapping.getFields().add(field);
            }

            ZipEntry methodsCsv = mcpZipFile.getEntry("conf/methods.csv");
            List<McpMember> methods = new CsvClientImpl<>(new InputStreamReader(mcpZipFile.getInputStream(methodsCsv)), McpMember.class).readBeans();

            for (McpMember method : methods) {
                if (method.getSide() != 0)
                    continue;

                McpClass mapping = mappings.get(method.getClassnotch());
                mapping.getMethods().add(method);
            }
        }

        try (TinyWriter writer = new TinyWriter(tinyFile, true, "official", "intermediary", "named")) {
            for (McpClass $class : mappings.values()) {
                String className = $class.getName().contains("/") ? $class.getName() : $class.getPackageName() + "/" + $class.getName();
                className = extraMappings.getClasses().getOrDefault(className, className);
                writer.acceptClass($class.getNotch(), className, className);

                for (McpMember field : $class.getFields()) {
                    writer.acceptField($class.getNotch(), field.getSig(), field.getNotch(), field.getSearge(), field.getName());
                }
                for (McpMember method : $class.getMethods()) {
                    writer.acceptMethod($class.getNotch(), method.getSig(), method.getNotch(), method.getSearge(), method.getName());
                }
            }
            writer.flush();
        }
    }
}
