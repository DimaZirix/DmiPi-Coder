package com.dmipi.coder.core.infrastructure.shell;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** PATH lookups for the sandbox providers' availability checks. */
public final class Executables {

    private Executables() {
    }

    public static boolean onPath(final String executable) {
        return Stream.of(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry).resolve(executable))
                .anyMatch(Files::isExecutable);
    }
}
