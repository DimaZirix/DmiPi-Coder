package com.dmipi.coder.core.plugins.claudeplugins;

import com.dmipi.coder.core.plugin.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Merges the servers of a plugin's {@code .mcp.json} into a native MCP config file: an existing
 * config keeps its other servers, a server of the same name is replaced, a missing config is
 * created. Transports are copied verbatim — the native MCP plugin decides what it supports.
 */
final class McpServersMerge {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SERVERS_FIELD = "mcpServers";

    private McpServersMerge() {
    }

    /** Merges every server into the config at {@code location}, returning the merged names. */
    static List<String> merge(final String pluginConfig, final FileSystem destination, final String location) {
        final JsonNode incoming = parse(pluginConfig, "the plugin's .mcp.json").path(SERVERS_FIELD);
        if (!incoming.isObject()) {
            throw new InstallFailure("The plugin's .mcp.json has no '" + SERVERS_FIELD + "' object — nothing to install.");
        }
        final Path file = destination.resolve(location);
        final ObjectNode root = existingConfig(destination, file, location);
        final ObjectNode servers = serversOf(root);
        final List<String> merged = new ArrayList<>();
        for (final String name : incoming.propertyNames()) {
            servers.set(name, incoming.get(name));
            merged.add(name);
        }
        destination.write(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        return List.copyOf(merged);
    }

    private static ObjectNode existingConfig(final FileSystem destination, final Path file, final String location) {
        if (!destination.exists(file)) {
            return MAPPER.createObjectNode();
        }
        final JsonNode existing = parse(destination.read(file), location);
        if (!existing.isObject()) {
            throw new InstallFailure("The existing MCP config " + location + " is not a JSON object; fix it before installing into it.");
        }
        return (ObjectNode) existing;
    }

    private static ObjectNode serversOf(final ObjectNode root) {
        final JsonNode present = root.path(SERVERS_FIELD);
        if (present.isObject()) {
            return (ObjectNode) present;
        }
        return root.putObject(SERVERS_FIELD);
    }

    private static JsonNode parse(final String json, final String describedSource) {
        try {
            return MAPPER.readTree(json);
        } catch (final JacksonException malformed) {
            throw new InstallFailure("Cannot parse " + describedSource + " as JSON: " + malformed.getMessage());
        }
    }
}
