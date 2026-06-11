package com.dmipi.coder.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces the layering by reading imports — no framework needed. The order the code follows:
 * domain → application → plugin (the SPI ports) → infrastructure (implements the ports) →
 * plugins (built-ins) → api. Domain stays framework-free, and no plugin imports another plugin.
 */
class ArchitectureTest {

    private static final Path SOURCES = Path.of("src/main/java/com/dmipi/coder/core");
    private static final String BASE = "com.dmipi.coder.core.";
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "domain", Set.of("domain"),
            "application", Set.of("domain", "application"),
            "plugin", Set.of("domain", "plugin"),
            "infrastructure", Set.of("domain", "application", "plugin", "infrastructure"),
            "plugins", Set.of("domain", "plugin", "infrastructure", "plugins"),
            "api", Set.of("domain", "application", "plugin", "infrastructure", "plugins", "api"));

    @Test
    @DisplayName("each layer imports only inward; domain is framework-free; no plugin imports another plugin")
    void should_respect_the_layering() throws IOException {
        final List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(file -> file.toString().endsWith(".java"))
                    .forEach(file -> check(file, violations));
        }
        assertThat(violations).isEmpty();
    }

    private static void check(final Path file, final List<String> violations) {
        final String layer = layerOf(file);
        final String ownPluginPackage = pluginPackageOf(file);
        final Matcher imports = IMPORT.matcher(read(file));
        while (imports.find()) {
            final String imported = imports.group(1);
            if (layer.equals("domain") && imported.startsWith("tools.jackson")) {
                violations.add(file + " -> " + imported + " (domain is framework-free)");
            }
            if (!imported.startsWith(BASE)) {
                continue;
            }
            final String importedLayer = imported.substring(BASE.length()).split("\\.")[0];
            if (!ALLOWED.get(layer).contains(importedLayer)) {
                violations.add(file + " -> " + imported + " (" + layer + " must not import " + importedLayer + ")");
            }
            if (layer.equals("plugins") && importedLayer.equals("plugins") && !imported.startsWith(BASE + "plugins." + ownPluginPackage + ".")) {
                violations.add(file + " -> " + imported + " (plugins never depend on other plugins)");
            }
        }
    }

    private static String layerOf(final Path file) {
        return SOURCES.relativize(file).getName(0).toString();
    }

    private static String pluginPackageOf(final Path file) {
        final Path relative = SOURCES.relativize(file);
        return relative.getNameCount() > 1 && relative.getName(0).toString().equals("plugins")
                ? relative.getName(1).toString()
                : "";
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not read " + file, failure);
        }
    }
}
