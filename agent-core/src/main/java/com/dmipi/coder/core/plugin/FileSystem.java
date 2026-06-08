package com.dmipi.coder.core.plugin;

import java.nio.file.Path;
import java.util.List;

/**
 * The file-system capability: read and edit files, resolved against the project directory.
 * Every path passes {@link #resolve} — a path escaping the project boundary is refused, so a
 * plugin holding this capability can never reach outside the project.
 */
public interface FileSystem {

    /** The absolute path for a user-supplied one, inside the boundary; an escaping path is refused loudly. */
    Path resolve(String userPath);

    String read(Path path);

    /** Writes the content, creating parent directories as needed. */
    void write(Path path, String content);

    /** The directory's entries, sorted, directories marked with a trailing slash. */
    List<String> list(Path directory);

    boolean exists(Path path);

    /** The file's size in bytes. */
    long size(Path path);

    /**
     * The regular files under the project matching a glob (e.g. {@code **}{@code /*.java}),
     * sorted, confined to the project boundary. Well-known noise directories (VCS internals,
     * build output, IDE metadata) are pruned from the walk. Used by the search tools.
     */
    List<Path> find(String glob);
}
