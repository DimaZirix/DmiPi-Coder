package com.dmipi.coder.core.plugin;

import java.nio.file.Path;
import java.util.Objects;

/** The configuration capability: the two anchors, read-only. */
public record Configuration(Path userDirectory, Path projectDirectory) {

    public Configuration {
        Objects.requireNonNull(userDirectory, "userDirectory");
        Objects.requireNonNull(projectDirectory, "projectDirectory");
    }
}
