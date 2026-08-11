package com.dmipi.coder.core.plugins.files;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which files were read this session, so {@code edit} and {@code write_file} can
 * refuse to change an existing file the model has not seen — the read-before-modify gate.
 * Shared between {@link FilesReadPlugin} and {@link FilesEditPlugin}; wiring one instance into
 * both turns the gate on. A successful write counts as a read: the model authored the content.
 */
public final class ReadTracker {

    private final Set<Path> read = ConcurrentHashMap.newKeySet();
    private final boolean enforcing;

    public ReadTracker() {
        this(true);
    }

    private ReadTracker(final boolean enforcing) {
        this.enforcing = enforcing;
    }

    /** The gate turned off: every file counts as read. The null-object for wirings without the gate. */
    public static ReadTracker off() {
        return new ReadTracker(false);
    }

    void markRead(final Path path) {
        read.add(path.toAbsolutePath().normalize());
    }

    boolean wasRead(final Path path) {
        return !enforcing || read.contains(path.toAbsolutePath().normalize());
    }
}
