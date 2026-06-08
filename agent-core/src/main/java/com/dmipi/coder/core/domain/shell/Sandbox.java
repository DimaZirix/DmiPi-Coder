package com.dmipi.coder.core.domain.shell;

import com.dmipi.coder.core.domain.agent.CancelToken;
import java.time.Duration;

/**
 * A live containment for shell commands. Built once per session by a provider; runs commands
 * inside the contract it was created with, and releases its resources on {@link #close}.
 */
public interface Sandbox extends AutoCloseable {

    /** Runs the command with the given (already clamped) timeout; cancellation is polled cooperatively. */
    ShellResult run(String command, Duration timeout, CancelToken cancel);

    /** The technology backing this sandbox, for display ("direct", "bubblewrap"). */
    String technology();

    /** True when this sandbox actually confines the command; false for the honest {@code direct} no-op. */
    boolean confines();

    @Override
    void close();
}
