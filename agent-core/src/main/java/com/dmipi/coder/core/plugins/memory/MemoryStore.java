package com.dmipi.coder.core.plugins.memory;

import com.dmipi.coder.core.plugin.FileSystem;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The memory files behind the tool: one conventional file per scope, common ecosystem names
 * recognized for the project scope, {@code @path} import lines inlined at load (depth-limited,
 * cycle-safe, confined to the scope's anchor).
 */
final class MemoryStore {

    private static final String USER_FILE = ".coder/CODER.md";
    private static final List<String> PROJECT_NAMES = List.of("CODER.md", "AGENTS.md", "CLAUDE.md");
    private static final int MAX_IMPORT_DEPTH = 3;

    private final FileSystem userFiles;
    private final FileSystem projectFiles;

    MemoryStore(final FileSystem userFiles, final FileSystem projectFiles) {
        this.userFiles = userFiles;
        this.projectFiles = projectFiles;
    }

    /** The scope's memory with imports inlined; empty when no memory file exists. */
    Optional<String> load(final MemoryScope scope) {
        final FileSystem files = files(scope);
        return existingFile(scope)
                .map(file -> inlined(files, file, files.read(file), MAX_IMPORT_DEPTH, new HashSet<>(Set.of(file))));
    }

    /** The target file's raw content as it is on disk — the "before" of a save diff; empty string when absent. */
    String rawContent(final MemoryScope scope) {
        final FileSystem files = files(scope);
        final Path target = targetFile(scope);
        return files.exists(target) ? files.read(target) : "";
    }

    void save(final MemoryScope scope, final String content) {
        files(scope).write(targetFile(scope), content);
    }

    /** The anchor-relative path a diff or preview names for the scope. */
    String targetLabel(final MemoryScope scope) {
        if (scope == MemoryScope.USER) {
            return USER_FILE;
        }
        return existingFile(MemoryScope.PROJECT)
                .map(file -> file.getFileName().toString())
                .orElse(PROJECT_NAMES.getFirst());
    }

    /** A save lands in the file that already holds the scope's memory, or the conventional name. */
    private Path targetFile(final MemoryScope scope) {
        if (scope == MemoryScope.USER) {
            return userFiles.resolve(USER_FILE);
        }
        return existingFile(MemoryScope.PROJECT).orElseGet(() -> projectFiles.resolve(PROJECT_NAMES.getFirst()));
    }

    private Optional<Path> existingFile(final MemoryScope scope) {
        if (scope == MemoryScope.USER) {
            final Path file = userFiles.resolve(USER_FILE);
            return userFiles.exists(file) ? Optional.of(file) : Optional.empty();
        }
        return PROJECT_NAMES.stream()
                .map(projectFiles::resolve)
                .filter(projectFiles::exists)
                .findFirst();
    }

    private FileSystem files(final MemoryScope scope) {
        return scope == MemoryScope.USER ? userFiles : projectFiles;
    }

    /** Inlines {@code @path} lines (a reference alone on a line), relative to the referencing file. */
    private static String inlined(final FileSystem files, final Path file, final String text, final int depth, final Set<Path> visited) {
        if (depth == 0) {
            return text;
        }
        return text.lines()
                .map(line -> isImport(line) ? resolvedImport(files, file, line.substring(1).trim(), depth, visited) : line)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static boolean isImport(final String line) {
        return line.startsWith("@") && line.length() > 1 && !line.substring(1).trim().contains(" ");
    }

    /** The referenced file's content, itself inlined; an unreadable, escaping or cyclic reference stays verbatim. */
    private static String resolvedImport(final FileSystem files, final Path file, final String reference, final int depth, final Set<Path> visited) {
        final Path anchor = files.resolve(".");
        final Path imported;
        try {
            imported = files.resolve(anchor.relativize(file.getParent().resolve(reference)).toString());
        } catch (final IllegalArgumentException escaping) {
            return "@" + reference;
        }
        if (!files.exists(imported) || !visited.add(imported)) {
            return "@" + reference;
        }
        return inlined(files, imported, files.read(imported), depth - 1, visited);
    }
}
