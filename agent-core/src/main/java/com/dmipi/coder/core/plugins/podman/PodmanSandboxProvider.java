package com.dmipi.coder.core.plugins.podman;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.ResourceLimits;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxNetwork;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import com.dmipi.coder.core.infrastructure.shell.Executables;
import java.time.Duration;
import java.util.Objects;

/**
 * The podman containment technology. The image carries the project's toolchain and is configured
 * explicitly ({@link #DEFAULT_IMAGE} otherwise); resource limits are optional. {@link #create}
 * resolves the proxied-egress netmode first (pasta preferred, slirp4netns fallback, loud refusal
 * when neither helper is installed), then runs a liveness conformance probe — a trivial command
 * must run in the container, under the resolved netmode — before handing the sandbox out; unlike
 * bubblewrap, there is no "write outside the mounts" check to run, because container isolation
 * makes such a write structurally impossible (it lands in the ephemeral layer, never the host).
 */
public final class PodmanSandboxProvider implements SandboxProvider {

    static final String TECHNOLOGY = "podman";

    /** A small default image; a real project overrides it with one that carries its build tools. */
    public static final String DEFAULT_IMAGE = "docker.io/library/alpine:latest";

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final String image;
    private final ResourceLimits limits;

    public PodmanSandboxProvider() {
        this(DEFAULT_IMAGE);
    }

    public PodmanSandboxProvider(final String image) {
        this(image, ResourceLimits.none());
    }

    public PodmanSandboxProvider(final String image, final ResourceLimits limits) {
        this.image = Objects.requireNonNull(image, "image");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public String technology() {
        return TECHNOLOGY;
    }

    @Override
    public boolean available() {
        return Executables.onPath("podman");
    }

    @Override
    public boolean confines() {
        return true;
    }

    @Override
    public Sandbox create(final SandboxSpec spec) {
        final PodmanSandbox sandbox = new PodmanSandbox(spec, image, limits, proxyNetwork(spec));
        final ShellResult probe = sandbox.run("echo probe", PROBE_TIMEOUT, new CancelToken());
        if (!probe.succeeded()) {
            throw new IllegalStateException("The podman sandbox cannot run the image '" + image + "' on this host: " + probe.stderr().strip());
        }
        return sandbox;
    }

    /** Required exactly when the spec is proxied; refused loudly when no loopback-exposing helper exists. */
    private static ProxyNetwork proxyNetwork(final SandboxSpec spec) {
        if (!(spec.network() instanceof SandboxNetwork.Proxied)) {
            return null;
        }
        return ProxyNetwork.autoSelect(Executables::onPath)
                .orElseThrow(() -> new IllegalStateException("The podman provider cannot route egress through the host-side proxy: reaching the loopback-bound control point needs the pasta or slirp4netns helper, and neither is installed. Install one, use the bubblewrap technology, or isolate/open the network."));
    }
}
