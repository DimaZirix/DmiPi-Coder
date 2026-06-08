package com.dmipi.coder.core.plugins.bubblewrap;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import com.dmipi.coder.core.infrastructure.shell.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Filesystem confinement with bubblewrap: the host view stays intact but read-only; writable are
 * only the project directory, the configured additional directories, and a private {@code /tmp}.
 * IPC and UTS namespaces are unshared; the process dies with the session.
 *
 * <p>Honest limits (v1): the network stays shared — egress control is the core's own future
 * control point, not this provider's — and the PID namespace stays shared so the session can
 * tear down the whole tree without a nested init or a {@code /proc} remount.
 */
final class BubblewrapSandbox implements Sandbox {

    private final SandboxSpec spec;

    BubblewrapSandbox(final SandboxSpec spec) {
        this.spec = spec;
    }

    @Override
    public ShellResult run(final String command, final Duration timeout, final CancelToken cancel) {
        return ProcessRunner.run(wrapped(command), spec.projectDirectory(), timeout, cancel);
    }

    private List<String> wrapped(final String command) {
        final List<String> argv = new ArrayList<>();
        argv.add("bwrap");
        argv.addAll(List.of("--ro-bind", "/", "/", "--dev", "/dev", "--tmpfs", "/tmp"));
        bind(argv, spec.projectDirectory());
        for (final Path writable : spec.additionalWritableDirectories()) {
            bind(argv, writable);
        }
        argv.addAll(List.of("--unshare-ipc", "--unshare-uts", "--die-with-parent", "--"));
        argv.addAll(ProcessRunner.systemShell(command));
        return argv;
    }

    private static void bind(final List<String> argv, final Path writable) {
        argv.addAll(List.of("--bind", writable.toString(), writable.toString()));
    }

    @Override
    public String technology() {
        return BubblewrapSandboxProvider.TECHNOLOGY;
    }

    @Override
    public boolean confines() {
        return true;
    }

    @Override
    public void close() {
    }
}
