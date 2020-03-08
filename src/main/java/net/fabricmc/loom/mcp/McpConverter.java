/*
 * Copyright 2020 Joseph Burton
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.mcp;

import net.fabricmc.loom.YarnGithubResolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public interface McpConverter {
    void convert(File mcJar, Path mcpZip, Path tinyFile, McpMappingContainer extraMappings) throws IOException;
}
