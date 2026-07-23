package com.dmipi.coder.core.domain.shell;

import java.util.Objects;

/**
 * The outcome of a shell command: exit code, captured output, and whether it was killed —
 * for exceeding its timeout, or because the caller cancelled. A killed run reports exit code
 * -1; the flags say why, so a cancel is never mistaken for a command failure.
 */
public record ShellResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean cancelled) {

    public ShellResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }

    /** True when the command completed by itself with exit code 0. */
    public boolean succeeded() {
        return exitCode == 0 && !timedOut && !cancelled;
    }
}
