package com.dmipi.coder.core.infrastructure.files;

import com.dmipi.coder.core.plugin.FileSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** The core's file-system capability: every path is resolved and confined inside the project directory. */
public final class AnchoredFileSystem implements FileSystem {

    private final Path projectDirectory;

    public AnchoredFileSystem(final Path projectDirectory) {
        this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory").toAbsolutePath().normalize();
    }

    @Override
    public Path resolve(final String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new IllegalArgumentException("A path is required.");
        }
        final Path resolved = projectDirectory.resolve(userPath).normalize();
        if (!resolved.startsWith(projectDirectory)) {
            throw new IllegalArgumentException("The path '" + userPath + "' escapes the project directory.");
        }
        return resolved;
    }

    @Override
    public String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not read " + path + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public void write(final Path path, final String content) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not write " + path + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public List<String> list(final Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .map(AnchoredFileSystem::entryName)
                    .sorted()
                    .toList();
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not list " + directory + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public boolean exists(final Path path) {
        return Files.exists(path);
    }

    private static String entryName(final Path entry) {
        return Files.isDirectory(entry) ? entry.getFileName() + "/" : entry.getFileName().toString();
    }
}
