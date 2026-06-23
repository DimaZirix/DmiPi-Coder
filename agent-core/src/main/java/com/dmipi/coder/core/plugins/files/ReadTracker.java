package com.dmipi.coder.core.plugins.files;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which files were read this session, so {@code edit} can refuse to change a file it
 * has not seen — the read-before-edit gate. Shared between {@link FilesReadPlugin}'s read tool
 * and {@link FilesEditPlugin}'s edit tool; wiring one instance into both turns the gate on.
 */
public final class ReadTracker {

    private final Set<Path> read = ConcurrentHashMap.newKeySet();

    void markRead(final Path path) {
        read.add(path.toAbsolutePath().normalize());
    }

    boolean wasRead(final Path path) {
        return read.contains(path.toAbsolutePath().normalize());
    }
}
