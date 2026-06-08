package com.dmipi.coder.core.domain.shell;

import java.util.Objects;

/** The outcome of a shell command: exit code, captured output, and whether it was killed for exceeding its timeout. */
public record ShellResult(int exitCode, String stdout, String stderr, boolean timedOut) {

    public ShellResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }

    public boolean succeeded() {
        return exitCode == 0 && !timedOut;
    }
}
