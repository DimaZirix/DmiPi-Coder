package com.dmipi.coder.core.plugins.podman;

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
 * Container confinement with podman: each command runs in an ephemeral ({@code --rm}) container
 * of the configured image, with the project directory and any additional directories bind-mounted
 * read-write and everything else isolated in the container. {@code --userns=keep-id} maps the
 * container user to the host user so files written to the mounts keep the right ownership.
 *
 * <p>Unlike bubblewrap, the host toolchain is <em>not</em> visible — the container sees only the
 * image's filesystem, so the image must carry whatever the project builds with. Honest limits
 * (v1): the container keeps podman's default (NAT'd) network; egress control is the core's own
 * future control point, not this provider's.
 */
final class PodmanSandbox implements Sandbox {

    private final SandboxSpec spec;
    private final String image;

    PodmanSandbox(final SandboxSpec spec, final String image) {
        this.spec = spec;
        this.image = image;
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
        final List<String> argv = new ArrayList<>();
        argv.addAll(List.of("podman", "run", "--rm", "-i", "--userns=keep-id"));
        mount(argv, spec.projectDirectory());
        for (final Path writable : spec.additionalWritableDirectories()) {
            mount(argv, writable);
        }
        argv.addAll(List.of("--workdir", spec.projectDirectory().toString(), image));
        argv.addAll(ProcessRunner.systemShell(command));
        return argv;
    }

    private static void mount(final List<String> argv, final Path writable) {
        argv.addAll(List.of("-v", writable + ":" + writable + ":rw"));
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
