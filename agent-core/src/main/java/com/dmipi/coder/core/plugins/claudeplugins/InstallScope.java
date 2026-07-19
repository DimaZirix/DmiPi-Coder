package com.dmipi.coder.core.plugins.claudeplugins;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Where installed content lands: the user anchor (available in every project) or the project
 * anchor. Each scope knows the native MCP config location the plugin's servers merge into —
 * mirroring where the native MCP plugin reads.
 */
enum InstallScope {
    USER(".coder/.mcp.json"),
    PROJECT(".mcp.json");

    private final String mcpConfigLocation;

    InstallScope(final String mcpConfigLocation) {
        this.mcpConfigLocation = mcpConfigLocation;
    }

    /** The scope named by a raw parameter value, empty for an unknown spelling. */
    static Optional<InstallScope> of(final String raw) {
        return Arrays.stream(values())
                .filter(scope -> scope.label().equals(raw))
                .findFirst();
    }

    static String validValues() {
        return Arrays.stream(values())
                .map(InstallScope::label)
                .collect(Collectors.joining(", "));
    }

    String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    String mcpConfigLocation() {
        return mcpConfigLocation;
    }
}
