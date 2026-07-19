package com.dmipi.coder.core.plugins.claudeplugins;

import java.util.List;
import java.util.Objects;

/** One installed plugin as the manifest records it: where it came from and what it put where. */
record InstalledPlugin(String name, String source, List<String> skills, List<String> mcpServers) {

    InstalledPlugin {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        skills = List.copyOf(skills);
        mcpServers = List.copyOf(mcpServers);
    }
}
