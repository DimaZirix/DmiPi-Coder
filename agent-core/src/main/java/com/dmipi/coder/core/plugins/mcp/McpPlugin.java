package com.dmipi.coder.core.plugins.mcp;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Connects to the MCP servers declared in {@code .mcp.json} (project root; user scope at
 * {@code .coder/.mcp.json}) and contributes each remote tool as {@code mcp__<server>__<tool>}
 * with the schema the server advertised. An unreachable or misbehaving server is skipped with
 * a warning — startup survives an offline server.
 */
public final class McpPlugin implements Plugin {

    private static final Logger LOGGER = Logger.getLogger(McpPlugin.class.getName());

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.HTTP, CapabilityType.FILE_SYSTEM, CapabilityType.CONFIGURATION);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        for (final McpServerConfig server : McpConfigLoader.load(capabilities.userFileSystem(), capabilities.fileSystem())) {
            installServer(registrar, capabilities, server);
        }
    }

    private static void installServer(final PluginRegistrar registrar, final Capabilities capabilities, final McpServerConfig server) {
        final McpClient client = new McpClient(capabilities.http(), server);
        try {
            for (final McpRemoteTool remote : client.connect()) {
                registrar.registerTool(new McpProxyTool(client, server.name(), remote));
            }
        } catch (final RuntimeException unreachable) {
            LOGGER.warning("MCP server '" + server.name() + "' at " + server.url() + " could not be connected; skipping it: " + unreachable.getMessage());
        }
    }
}
