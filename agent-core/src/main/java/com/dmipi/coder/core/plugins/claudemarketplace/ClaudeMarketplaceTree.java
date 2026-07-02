package com.dmipi.coder.core.plugins.claudemarketplace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds files by name across the operator-supplied marketplace directories. These roots are
 * registered in Java (not sandbox-anchored), so they are walked directly; any {@code .git}
 * metadata is skipped.
 */
final class ClaudeMarketplaceTree {

    private static final String VCS_DIRECTORY = ".git";
    private static final int MAX_DEPTH = 8;

    private ClaudeMarketplaceTree() {
    }

    static List<Path> filesNamed(final List<Path> roots, final String fileName) {
        final List<Path> matches = new ArrayList<>();
        for (final Path root : roots) {
            if (Files.isDirectory(root)) {
                matches.addAll(within(root, fileName));
            }
        }
        return List.copyOf(matches);
    }

    /** The file's contents, or empty when it cannot be read — an unreadable entry is skipped, not fatal to discovery. */
    static Optional<String> read(final Path file) {
        try {
            return Optional.of(Files.readString(file));
        } catch (final IOException unreadable) {
            return Optional.empty();
        }
    }

    private static List<Path> within(final Path root, final String fileName) {
        try (Stream<Path> tree = Files.walk(root, MAX_DEPTH)) {
            return tree
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .filter(ClaudeMarketplaceTree::notInsideVcs)
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Could not scan the marketplace directory " + root, unreadable);
        }
    }

    private static boolean notInsideVcs(final Path path) {
        for (final Path segment : path) {
            if (segment.toString().equals(VCS_DIRECTORY)) {
                return false;
            }
        }
        return true;
    }
}
