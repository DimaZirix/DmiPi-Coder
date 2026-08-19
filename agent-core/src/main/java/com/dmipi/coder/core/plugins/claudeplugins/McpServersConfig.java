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
 * Edits a native MCP config file at the server granularity: merge the servers of a plugin's
 * {@code .mcp.json} in (other servers kept, a same-name server replaced, a missing config
 * created), or remove servers by name (other servers and unrelated fields kept). Transports are
 * copied verbatim — the native MCP plugin decides what it supports.
 */
final class McpServersConfig {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SERVERS_FIELD = "mcpServers";

    private McpServersConfig() {
    }

    /**
     * The validated {@code mcpServers} object of a plugin's config — parsed up front so a
     * malformed config fails the install before anything was copied.
     */
    static ObjectNode incomingServers(final String pluginConfig) {
        final JsonNode servers = parse(pluginConfig, "the plugin's .mcp.json").path(SERVERS_FIELD);
        if (!servers.isObject()) {
            throw new InstallFailure("The plugin's .mcp.json has no '" + SERVERS_FIELD + "' object — nothing to install.");
        }
        return (ObjectNode) servers;
    }

    /** Parses the destination config up front — a corrupt one must fail the install before any skill was written. */
    static void validateDestination(final FileSystem destination, final String location) {
        existingConfig(destination, destination.resolve(location), location);
    }

    /** Merges every server into the config at {@code location}, returning the merged names. */
    static List<String> merge(final ObjectNode incoming, final FileSystem destination, final String location) {
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

    /** Removes the named servers from the config at {@code location}; a missing config is a no-op. */
    static void remove(final List<String> names, final FileSystem destination, final String location) {
        final Path file = destination.resolve(location);
        if (!destination.exists(file)) {
            return;
        }
        final ObjectNode root = existingConfig(destination, file, location);
        final ObjectNode servers = serversOf(root);
        names.forEach(servers::remove);
        destination.write(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
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
