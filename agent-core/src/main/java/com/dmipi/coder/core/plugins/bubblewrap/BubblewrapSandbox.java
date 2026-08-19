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
 * IPC and UTS namespaces are unshared; the process dies with the session. When resource limits
 * are configured, the whole thing runs inside a {@code systemd-run --user --scope} so the bounds
 * bubblewrap lacks (memory, task count) are enforced by the user's cgroup.
 *
 * <p>Honest limits (v1): the network stays shared — egress control is the core's own future
 * control point, not this provider's — and the PID namespace stays shared so the session can
 * tear down the whole tree without a nested init or a {@code /proc} remount.
 */
final class BubblewrapSandbox implements Sandbox {

    private final SandboxSpec spec;
    private final ResourceLimits limits;

    BubblewrapSandbox(final SandboxSpec spec, final ResourceLimits limits) {
        this.spec = spec;
        this.limits = limits;
    }

    @Override
    public ShellResult run(final String command, final Duration timeout, final CancelToken cancel) {
        return ProcessRunner.run(wrapped(command), spec.projectDirectory(), timeout, cancel);
    }

    @Override
    public Process startBackground(final String command) {
        return ProcessRunner.start(wrapped(command), spec.projectDirectory());
    }

    List<String> wrapped(final String command) {
        final List<String> argv = new ArrayList<>(systemdRunPrefix());
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

    private List<String> systemdRunPrefix() {
        if (!limits.bounded()) {
            return List.of();
        }

        final List<String> prefix = new ArrayList<>(List.of("systemd-run", "--user", "--scope", "--quiet"));
        if (limits.limitsMemory()) {
            prefix.addAll(List.of("-p", "MemoryMax=" + limits.memoryMax()));
        }
        if (limits.limitsTasks()) {
            prefix.addAll(List.of("-p", "TasksMax=" + limits.tasksMax()));
        }
        return prefix;
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
