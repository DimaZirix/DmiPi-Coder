package com.dmipi.coder.core.plugins.mcp;

import com.dmipi.coder.core.plugin.FileSystem;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads MCP server declarations: {@code .mcp.json} at the project root and
 * {@code .coder/.mcp.json} under the user directory. Only the {@code http} transport is
 * supported — a server of another type is skipped with a warning, so a shared config can carry
 * transports this agent does not speak without breaking startup. A malformed file or an invalid
 * value, by contrast, fails startup loudly: a broken config is a config error, not an offline
 * server. On a name clash, project wins.
 */
final class McpConfigLoader {

    private static final Logger LOGGER = Logger.getLogger(McpConfigLoader.class.getName());
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String HTTP_TYPE = "http";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private McpConfigLoader() {
    }

    static List<McpServerConfig> load(final FileSystem userFiles, final FileSystem projectFiles) {
        final Map<String, McpServerConfig> byName = new LinkedHashMap<>();
        collect(byName, userFiles, ".coder/.mcp.json");
        collect(byName, projectFiles, ".mcp.json");
        return List.copyOf(byName.values());
    }

    private static void collect(final Map<String, McpServerConfig> byName, final FileSystem files, final String location) {
        final Path file = files.resolve(location);
        if (!files.exists(file)) {
            return;
        }
        final JsonNode servers;
        try {
            servers = MAPPER.readTree(files.read(file)).path("mcpServers");
        } catch (final JacksonException malformed) {
            throw new IllegalStateException("MCP config " + file + " is not valid JSON — fix or remove it: " + malformed.getMessage(), malformed);
        }
        if (!servers.isObject()) {
            throw new IllegalStateException("MCP config " + file + " has no 'mcpServers' object; declare servers under that key, or remove the file.");
        }
        for (final String name : servers.propertyNames()) {
            final McpServerConfig server = server(name, servers.path(name), file);
            if (server != null) {
                byName.put(name, server);
            }
        }
    }

    private static McpServerConfig server(final String name, final JsonNode declaration, final Path file) {
        final String type = declaration.path("type").isString() ? declaration.path("type").stringValue() : "";
        if (!HTTP_TYPE.equals(type)) {
            LOGGER.warning("MCP server '" + name + "' in " + file + " has unsupported type '" + type + "' (only '" + HTTP_TYPE + "'); skipping it.");
            return null;
        }
        final JsonNode url = declaration.path("url");
        if (!url.isString() || url.stringValue().isBlank()) {
            throw new IllegalStateException("MCP server '" + name + "' in " + file + " has no 'url'; declare one or remove the server.");
        }
        final JsonNode timeout = declaration.path("timeout");
        if (!timeout.isMissingNode() && (!timeout.isIntegralNumber() || timeout.longValue() <= 0)) {
            throw new IllegalStateException("MCP server '" + name + "' in " + file + " has an invalid 'timeout' (" + timeout + "); use positive milliseconds.");
        }
        return new McpServerConfig(name, url.stringValue(), timeout.isIntegralNumber() ? Duration.ofMillis(timeout.longValue()) : DEFAULT_REQUEST_TIMEOUT);
    }
}
