package com.dmipi.coder.core.infrastructure.shell;

import com.dmipi.coder.core.application.egress.EgressPolicy;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxNetwork;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import com.dmipi.coder.core.infrastructure.shell.egress.EgressProxy;
import com.dmipi.coder.core.plugin.Shell;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The core's shell capability. Owns the containment policy (the {@link SandboxSpec}) and clamps
 * every requested timeout to the configured maximum; the mechanism comes from the configured
 * {@link SandboxProvider}. The sandbox is built lazily on first use, so a session that never
 * runs a command allocates nothing, and torn down at {@link #close} — after which further
 * commands are refused rather than silently resurrecting an untracked sandbox. With an egress
 * policy, the control-point proxy shares that lifecycle exactly: started with the sandbox,
 * closed with the session.
 */
public final class SessionShell implements Shell, AutoCloseable {

    private static final int PROXY_TOKEN_BYTES = 16;

    private final SandboxProvider provider;
    private final SandboxSpec spec;
    private final EgressPolicy egressPolicy;
    private final List<Process> backgroundProcesses = new CopyOnWriteArrayList<>();
    private final AtomicInteger backgroundCounter = new AtomicInteger();
    // Guarded by this: sandbox + proxy lifecycle — lazily built, torn down once, never rebuilt after close.
    private Sandbox sandbox;
    private EgressProxy proxy;
    private String proxyToken;
    private boolean closed;

    public SessionShell(final SandboxProvider provider, final SandboxSpec spec) {
        this(provider, spec, null);
    }

    /** With a non-null policy, the shell starts the egress proxy alongside the sandbox and resolves the spec's network to it. */
    public SessionShell(final SandboxProvider provider, final SandboxSpec spec, final EgressPolicy egressPolicy) {
        this.provider = provider;
        this.spec = spec;
        this.egressPolicy = egressPolicy;
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
            sandbox = provider.create(egressPolicy == null ? spec : proxiedSpec());
        }
        return sandbox;
    }

    /** Starts (or reuses, when a previous create failed after starting it) the proxy and resolves the network to it. */
    private SandboxSpec proxiedSpec() {
        if (proxy == null) {
            proxyToken = newProxyToken();
            proxy = new EgressProxy(egressPolicy::allows, proxyToken);
        }
        return spec.withNetwork(new SandboxNetwork.Proxied(proxy.port(), proxyToken));
    }

    /** Per-session secret: another instance's sandbox on the same loopback cannot borrow this proxy's policy. */
    private static String newProxyToken() {
        final byte[] bytes = new byte[PROXY_TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
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
        if (proxy != null) {
            proxy.close();
            proxy = null;
        }
    }
}
