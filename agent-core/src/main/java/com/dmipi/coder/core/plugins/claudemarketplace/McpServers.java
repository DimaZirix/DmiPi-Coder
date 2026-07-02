package com.dmipi.coder.core.plugins.claudemarketplace;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the {@code mcpServers} object of an {@code .mcp.json} document into http server configs.
 * Only the {@code http} transport is supported — a server of another type, or one missing a url,
 * is skipped with a warning, so a shared config can carry transports this agent does not speak
 * without breaking startup. The {@code source} names the origin file for those warnings.
 */
final class McpServers {

    private static final Logger LOGGER = Logger.getLogger(McpServers.class.getName());
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String HTTP_TYPE = "http";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private McpServers() {
    }

    static List<McpServerConfig> from(final String json, final String source) {
        final JsonNode servers;
        try {
            servers = MAPPER.readTree(json).path("mcpServers");
        } catch (final JacksonException malformed) {
            LOGGER.warning("MCP config " + source + " is not valid JSON; ignoring it: " + malformed.getMessage());
            return List.of();
        }
        if (!servers.isObject()) {
            LOGGER.warning("MCP config " + source + " has no 'mcpServers' object; ignoring it.");
            return List.of();
        }
        final List<McpServerConfig> configs = new ArrayList<>();
        for (final String name : servers.propertyNames()) {
            final McpServerConfig server = server(name, servers.path(name), source);
            if (server != null) {
                configs.add(server);
            }
        }
        return List.copyOf(configs);
    }

    private static McpServerConfig server(final String name, final JsonNode declaration, final String source) {
        final String type = declaration.path("type").isString() ? declaration.path("type").stringValue() : "";
        if (!HTTP_TYPE.equals(type)) {
            LOGGER.warning("MCP server '" + name + "' in " + source + " has unsupported type '" + type + "' (only '" + HTTP_TYPE + "'); skipping it.");
            return null;
        }
        final JsonNode url = declaration.path("url");
        if (!url.isString() || url.stringValue().isBlank()) {
            LOGGER.warning("MCP server '" + name + "' in " + source + " has no 'url'; skipping it.");
            return null;
        }
        final Duration timeout = declaration.path("timeout").isIntegralNumber()
                ? Duration.ofMillis(declaration.path("timeout").longValue())
                : DEFAULT_REQUEST_TIMEOUT;
        return new McpServerConfig(name, url.stringValue(), timeout);
    }
}
