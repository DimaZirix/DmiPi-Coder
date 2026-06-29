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
 * runs a command allocates nothing, and torn down at {@link #close}.
 */
public final class SessionShell implements Shell, AutoCloseable {

    private final SandboxProvider provider;
    private final SandboxSpec spec;
    private final List<Process> backgroundProcesses = new CopyOnWriteArrayList<>();
    private final AtomicInteger backgroundCounter = new AtomicInteger();
    private volatile Sandbox sandbox;

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
        return run(command, Optional.empty(), cancel);
    }

    @Override
    public ShellResult run(final String command, final Optional<Duration> timeout, final CancelToken cancel) {
        return sandbox().run(command, clamped(timeout), cancel);
    }

    @Override
    public String runInBackground(final String command) {
        final Process process = sandbox().startBackground(command);
        backgroundProcesses.add(process);
        return "bg-" + backgroundCounter.incrementAndGet();
    }

    private Duration clamped(final Optional<Duration> requested) {
        final Duration timeout = requested.orElse(spec.defaultTimeout());
        return timeout.compareTo(spec.maxTimeout()) > 0 ? spec.maxTimeout() : timeout;
    }

    private Sandbox sandbox() {
        Sandbox current = sandbox;
        if (current == null) {
            synchronized (this) {
                current = sandbox;
                if (current == null) {
                    current = provider.create(spec);
                    sandbox = current;
                }
            }
        }
        return current;
    }

    @Override
    public void close() {
        backgroundProcesses.forEach(ProcessRunner::killProcessTree);
        backgroundProcesses.clear();
        final Sandbox current = sandbox;
        if (current != null) {
            current.close();
        }
    }
}
