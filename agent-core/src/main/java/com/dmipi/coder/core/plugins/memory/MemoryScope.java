package com.dmipi.coder.core.plugins.memory;

/** Where a memory entry lives: the user's own file (applies everywhere) or the project's. */
enum MemoryScope {
    USER,
    PROJECT;

    static MemoryScope of(final String wire) {
        return switch (wire) {
            case "user" -> USER;
            case "project" -> PROJECT;
            default -> throw new IllegalArgumentException("Parameter 'scope' must be 'user' or 'project'.");
        };
    }

    String label() {
        return name().toLowerCase();
    }
}
