package com.dmipi.coder.core.infrastructure.files;

import com.dmipi.coder.core.plugin.FileSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** The core's file-system capability: every path is resolved and confined inside the project directory. */
public final class AnchoredFileSystem implements FileSystem {

    /** Noise the search walk prunes: VCS internals, build output, dependency caches, IDE and agent metadata. */
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(".git", ".idea", ".llmcode", "target", "build", "dist", "out", "node_modules");

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

    @Override
    public long size(final Path path) {
        try {
            return Files.size(path);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not read the size of " + path + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public List<Path> find(final String glob) {
        if (glob == null || glob.isBlank()) {
            throw new IllegalArgumentException("A glob pattern is required.");
        }
        final PathMatcher matcher = rootTolerantMatcher(glob);
        final List<Path> matches = new ArrayList<>();
        try {
            Files.walkFileTree(projectDirectory, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
                    if (!directory.equals(projectDirectory) && IGNORED_DIRECTORIES.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && matcher.matches(projectDirectory.relativize(file))) {
                        matches.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException unreadable) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not search " + projectDirectory + ": " + failure.getMessage(), failure);
        }
        matches.sort(Path::compareTo);
        return List.copyOf(matches);
    }

    /**
     * A {@code **}{@code /} prefix means "at any depth" — but Java's glob requires a separator,
     * so it would miss root-level files. This matches the pattern, or (for a leading
     * {@code **}{@code /}) the remainder against a root-level file, the way ripgrep and git do.
     */
    private PathMatcher rootTolerantMatcher(final String glob) {
        final PathMatcher direct = projectDirectory.getFileSystem().getPathMatcher("glob:" + glob);
        if (!glob.startsWith("**/")) {
            return direct;
        }
        final PathMatcher rootLevel = projectDirectory.getFileSystem().getPathMatcher("glob:" + glob.substring("**/".length()));
        return path -> direct.matches(path) || rootLevel.matches(path);
    }

    private static String entryName(final Path entry) {
        return Files.isDirectory(entry) ? entry.getFileName() + "/" : entry.getFileName().toString();
    }
}
