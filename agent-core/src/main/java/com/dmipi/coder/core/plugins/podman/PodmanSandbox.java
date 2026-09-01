package com.dmipi.coder.core.plugins.podman;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.ResourceLimits;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxNetwork;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import com.dmipi.coder.core.infrastructure.shell.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Container confinement with podman: each command runs in an ephemeral ({@code --rm}) container
 * of the configured image, with the project directory and any additional directories bind-mounted
 * read-write and everything else isolated in the container. {@code --userns=keep-id} maps the
 * container user to the host user so files written to the mounts keep the right ownership;
 * {@code --security-opt=no-new-privileges} blocks setuid escalation. Resource limits are podman's
 * own cgroup flags. The foreground timeout is enforced <em>inside</em> the boundary too
 * ({@code --timeout}): killing the host-side client cannot reach conmon-supervised processes.
 *
 * <p>Unlike bubblewrap, the host toolchain is <em>not</em> visible — the container sees only the
 * image's filesystem, so the image must carry whatever the project builds with. An isolated
 * network becomes {@code --network=none}; a proxied network rides a {@link ProxyRoute} — a
 * netmode exposing the host loopback at a per-session address — with DNS pointed at the
 * container's own (empty) loopback so
 * direct-by-hostname egress is blackholed. Honest limits (v1): an open network keeps podman's
 * default (NAT'd) network, and a <em>background</em> container is not torn down by the session —
 * killing the host-side client does not stop it.
 */
final class PodmanSandbox implements Sandbox {

    private static final int MILLIS_PER_SECOND = 1_000;

    private final SandboxSpec spec;
    private final String image;
    private final ResourceLimits limits;
    private final ProxyRoute proxyRoute;

    /** {@code proxyRoute} is required exactly when the spec's network is proxied; null otherwise. */
    PodmanSandbox(final SandboxSpec spec, final String image, final ResourceLimits limits, final ProxyRoute proxyRoute) {
        this.spec = spec;
        this.image = image;
        this.limits = limits;
        this.proxyRoute = proxyRoute;
    }

    @Override
    public ShellResult run(final String command, final Duration timeout, final CancelToken cancel) {
        return ProcessRunner.run(wrapped(command, timeout), spec.projectDirectory(), timeout, cancel);
    }

    @Override
    public Process startBackground(final String command) {
        return ProcessRunner.start(wrapped(command, Duration.ZERO), spec.projectDirectory());
    }

    /** A zero timeout means unbounded — the background case. */
    List<String> wrapped(final String command, final Duration timeout) {
        final List<String> argv = new ArrayList<>();
        argv.addAll(List.of("podman", "run", "--rm", "-i", "--userns=keep-id", "--security-opt=no-new-privileges"));
        if (!timeout.isZero()) {
            argv.addAll(List.of("--timeout", String.valueOf(Math.ceilDiv(timeout.toMillis(), MILLIS_PER_SECOND))));
        }
        network(argv);
        resourceLimits(argv);
        mount(argv, spec.projectDirectory());
        for (final Path writable : spec.additionalWritableDirectories()) {
            mount(argv, writable);
        }
        argv.addAll(List.of("--workdir", spec.projectDirectory().toString(), image));
        argv.addAll(ProcessRunner.systemShell(command));
        return argv;
    }

    private void network(final List<String> argv) {
        if (spec.network() instanceof SandboxNetwork.Isolated) {
            argv.add("--network=none");
            return;
        }
        if (spec.network() instanceof SandboxNetwork.Proxied proxied) {
            argv.add(proxyRoute.flag());
            argv.addAll(List.of("--dns", "127.0.0.1"));
            proxyEnvironment(argv, proxied, proxyRoute.hostLoopback());
        }
    }

    /** Credentials in the URL become Proxy-Authorization in every proxy-honoring client. */
    private static void proxyEnvironment(final List<String> argv, final SandboxNetwork.Proxied proxied, final String hostLoopback) {
        final String credentials = proxied.token().isEmpty() ? "" : "coder:" + proxied.token() + "@";
        final String proxyUrl = "http://" + credentials + hostLoopback + ":" + proxied.port();
        for (final String name : List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")) {
            argv.addAll(List.of("-e", name + "=" + proxyUrl));
        }
    }

    private void resourceLimits(final List<String> argv) {
        if (limits.limitsMemory()) {
            argv.addAll(List.of("--memory", limits.memoryMax()));
        }
        if (limits.limitsTasks()) {
            argv.addAll(List.of("--pids-limit", String.valueOf(limits.tasksMax())));
        }
    }

    /** --mount instead of -v: a path containing ':' breaks the -v spec; a comma breaks both, so it is refused loudly. */
    private static void mount(final List<String> argv, final Path writable) {
        final String path = writable.toString();
        if (path.contains(",")) {
            throw new IllegalArgumentException("The mount path '" + path + "' contains a comma, which podman's mount syntax cannot carry.");
        }
        argv.addAll(List.of("--mount", "type=bind,source=" + path + ",destination=" + path));
    }

    @Override
    public String technology() {
        return PodmanSandboxProvider.TECHNOLOGY;
    }

    @Override
    public boolean confines() {
        return true;
    }

    @Override
    public void close() {
    }
}
