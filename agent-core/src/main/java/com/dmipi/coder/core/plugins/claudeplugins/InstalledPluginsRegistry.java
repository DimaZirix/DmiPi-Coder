package com.dmipi.coder.core.plugins.claudeplugins;

import com.dmipi.coder.core.plugin.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The per-anchor manifest of installed plugins — {@code .coder/installed-plugins.json} — the
 * provenance that makes listing and removal possible after installation converted everything
 * to the native layout. An entry records the source and the skill and server names it put there.
 */
final class InstalledPluginsRegistry {

    private static final String LOCATION = ".coder/installed-plugins.json";
    private static final String PLUGINS_FIELD = "plugins";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private InstalledPluginsRegistry() {
    }

    static List<InstalledPlugin> all(final FileSystem files) {
        final JsonNode plugins = manifest(files).path(PLUGINS_FIELD);
        final List<InstalledPlugin> installed = new ArrayList<>();
        for (final String name : plugins.propertyNames()) {
            installed.add(entry(name, plugins.path(name)));
        }
        return List.copyOf(installed);
    }

    static Optional<InstalledPlugin> find(final FileSystem files, final String name) {
        return all(files).stream()
                .filter(plugin -> plugin.name().equals(name))
                .findFirst();
    }

    /** Records the plugin, replacing a previous entry of the same name. */
    static void record(final FileSystem files, final InstalledPlugin plugin) {
        final ObjectNode root = manifest(files);
        final ObjectNode entry = pluginsOf(root).putObject(plugin.name());
        entry.put("source", plugin.source());
        names(entry.putArray("skills"), plugin.skills());
        names(entry.putArray("mcpServers"), plugin.mcpServers());
        files.write(files.resolve(LOCATION), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    /** Drops the entry; the last entry removed deletes the manifest file itself. */
    static void remove(final FileSystem files, final String name) {
        final ObjectNode root = manifest(files);
        final ObjectNode plugins = pluginsOf(root);
        plugins.remove(name);
        final Path file = files.resolve(LOCATION);
        if (plugins.isEmpty()) {
            files.delete(file);
            return;
        }
        files.write(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private static InstalledPlugin entry(final String name, final JsonNode node) {
        return new InstalledPlugin(
                name,
                text(node.path("source")),
                strings(node.path("skills")),
                strings(node.path("mcpServers")));
    }

    private static List<String> strings(final JsonNode array) {
        final List<String> values = new ArrayList<>();
        for (final JsonNode value : array) {
            values.add(text(value));
        }
        return values;
    }

    private static String text(final JsonNode node) {
        return node.isString() ? node.stringValue() : "";
    }

    private static void names(final ArrayNode array, final List<String> values) {
        values.forEach(array::add);
    }

    private static ObjectNode manifest(final FileSystem files) {
        final Path file = files.resolve(LOCATION);
        if (!files.exists(file)) {
            return MAPPER.createObjectNode();
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(files.read(file));
        } catch (final JacksonException malformed) {
            throw new InstallFailure("The plugin manifest " + LOCATION + " is not valid JSON; fix it before continuing: " + malformed.getMessage());
        }
        if (!root.isObject()) {
            throw new InstallFailure("The plugin manifest " + LOCATION + " is not a JSON object; fix it before continuing.");
        }
        return (ObjectNode) root;
    }

    private static ObjectNode pluginsOf(final ObjectNode root) {
        final JsonNode present = root.path(PLUGINS_FIELD);
        if (present.isObject()) {
            return (ObjectNode) present;
        }
        return root.putObject(PLUGINS_FIELD);
    }
}
