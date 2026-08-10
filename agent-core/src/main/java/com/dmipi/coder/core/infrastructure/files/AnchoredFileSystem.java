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

/** The core's file-system capability: every path is resolved and confined inside its anchor directory (the project, or the user directory for user-scope state). */
public final class AnchoredFileSystem implements FileSystem {

    /** Noise the search walk prunes: VCS internals, build output, dependency caches, IDE and agent metadata. */
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(".git", ".idea", ".llmcode", "target", "build", "dist", "out", "node_modules");

    private final Path anchor;

    public AnchoredFileSystem(final Path anchor) {
        this.anchor = Objects.requireNonNull(anchor, "anchor").toAbsolutePath().normalize();
    }

    @Override
    public Path resolve(final String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new IllegalArgumentException("A path is required.");
        }
        final Path resolved = anchor.resolve(userPath).normalize();
        if (!resolved.startsWith(anchor)) {
            throw new IllegalArgumentException("The path '" + userPath + "' escapes the anchored directory.");
        }
        return resolved;
    }

    @Override
    public String read(final Path path) {
        try {
            return Files.readString(confined(path));
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not read " + path + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public void write(final Path path, final String content) {
        try {
            final Path target = confined(path);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not write " + path + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public void delete(final Path path) {
        final Path target = confined(path);
        if (!Files.exists(target)) {
            return;
        }
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path directory, final IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not delete " + path + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public List<String> list(final Path directory) {
        try (Stream<Path> entries = Files.list(confined(directory))) {
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
        return Files.exists(confined(path));
    }

    @Override
    public long size(final Path path) {
        try {
            return Files.size(confined(path));
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not read the size of " + path + ": " + failure.getMessage(), failure);
        }
    }

    /**
     * Every accessor re-checks the anchor, so confinement holds by construction — not by the
     * caller's discipline of only passing {@link #resolve}d paths.
     */
    private Path confined(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(anchor)) {
            throw new IllegalArgumentException("The path '" + path + "' escapes the anchored directory " + anchor + ".");
        }
        return normalized;
    }

    @Override
    public List<Path> find(final String glob) {
        if (glob == null || glob.isBlank()) {
            throw new IllegalArgumentException("A glob pattern is required.");
        }
        final PathMatcher matcher = rootTolerantMatcher(glob);
        final List<Path> matches = new ArrayList<>();
        try {
            Files.walkFileTree(anchor, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
                    if (!directory.equals(anchor) && IGNORED_DIRECTORIES.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && matcher.matches(anchor.relativize(file))) {
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
            throw new UncheckedIOException("Could not search " + anchor + ": " + failure.getMessage(), failure);
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
        final PathMatcher direct = anchor.getFileSystem().getPathMatcher("glob:" + glob);
        if (!glob.startsWith("**/")) {
            return direct;
        }
        final PathMatcher rootLevel = anchor.getFileSystem().getPathMatcher("glob:" + glob.substring("**/".length()));
        return path -> direct.matches(path) || rootLevel.matches(path);
    }

    private static String entryName(final Path entry) {
        return Files.isDirectory(entry) ? entry.getFileName() + "/" : entry.getFileName().toString();
    }
}
