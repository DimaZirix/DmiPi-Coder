package com.dmipi.coder.core.infrastructure.shell;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import com.dmipi.coder.core.plugin.Shell;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The core's shell capability. Owns the containment policy (the {@link SandboxSpec}) and clamps
 * every requested timeout to the configured maximum; the mechanism comes from the configured
 * {@link SandboxProvider}. The sandbox is built lazily on first use, so a session that never
 * runs a command allocates nothing, and torn down at {@link #close} — after which further
 * commands are refused rather than silently resurrecting an untracked sandbox.
 */
public final class SessionShell implements Shell, AutoCloseable {

    private final SandboxProvider provider;
    private final SandboxSpec spec;
    private final List<Process> backgroundProcesses = new CopyOnWriteArrayList<>();
    private final AtomicInteger backgroundCounter = new AtomicInteger();
    // Guarded by this: sandbox lifecycle — lazily built, torn down once, never rebuilt after close.
    private Sandbox sandbox;
    private boolean closed;

    public SessionShell(final SandboxProvider provider, final SandboxSpec spec) {
        this.provider = provider;
        this.spec = spec;
    }

    /** True when the configured provider actually confines commands — known without building the sandbox. */
    public boolean confines() {
        return provider.confines();
    }

    @Override
    public ShellResult run(final String command, final CancelToken cancel) {
        return run(command, spec.defaultTimeout(), cancel);
    }

    @Override
    public ShellResult run(final String command, final Duration timeout, final CancelToken cancel) {
        return sandbox().run(command, clamped(timeout), cancel);
    }

    @Override
    public synchronized String runInBackground(final String command) {
        // Synchronized with close(): a background process either registers before the kill loop or is refused.
        final Process process = sandbox().startBackground(command);
        backgroundProcesses.add(process);
        return "bg-" + backgroundCounter.incrementAndGet();
    }

    private Duration clamped(final Duration requested) {
        return requested.compareTo(spec.maxTimeout()) > 0 ? spec.maxTimeout() : requested;
    }

    private synchronized Sandbox sandbox() {
        if (closed) {
            throw new IllegalStateException("The session shell is closed; no further commands can run.");
        }
        if (sandbox == null) {
            sandbox = provider.create(spec);
        }
        return sandbox;
    }

    @Override
    public synchronized void close() {
        closed = true;
        backgroundProcesses.forEach(ProcessRunner::killProcessTree);
        backgroundProcesses.clear();
        if (sandbox != null) {
            sandbox.close();
            sandbox = null;
        }
    }
}
