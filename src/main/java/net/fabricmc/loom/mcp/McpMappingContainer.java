package net.fabricmc.loom.mcp;

import net.fabricmc.loom.YarnGithubResolver;

public class McpMappingContainer extends YarnGithubResolver.MappingContainer {
    public McpMappingContainer(String minecraftVersion) {
        super(minecraftVersion);
    }

    private String conf = "conf";

    public String getConf() {
        return conf;
    }

    public void setConf(String conf) {
        this.conf = conf;
    }
}
