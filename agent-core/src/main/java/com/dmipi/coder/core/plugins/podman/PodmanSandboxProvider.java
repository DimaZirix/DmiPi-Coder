package com.dmipi.coder.core.plugins.podman;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The podman containment technology. The image carries the project's toolchain and is configured
 * explicitly ({@link #DEFAULT_IMAGE} otherwise). {@link #create} runs a liveness conformance
 * probe — a trivial command must run in the container — before handing the sandbox out; unlike
 * bubblewrap, there is no "write outside the mounts" check to run, because container isolation
 * makes such a write structurally impossible (it lands in the ephemeral layer, never the host).
 */
public final class PodmanSandboxProvider implements SandboxProvider {

    static final String TECHNOLOGY = "podman";

    /** A small default image; a real project overrides it with one that carries its build tools. */
    public static final String DEFAULT_IMAGE = "docker.io/library/alpine:latest";

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final String image;

    public PodmanSandboxProvider() {
        this(DEFAULT_IMAGE);
    }

    public PodmanSandboxProvider(final String image) {
        this.image = Objects.requireNonNull(image, "image");
    }

    @Override
    public String technology() {
        return TECHNOLOGY;
    }

    @Override
    public boolean available() {
        return Stream.of(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry).resolve("podman"))
                .anyMatch(Files::isExecutable);
    }

    @Override
    public boolean confines() {
        return true;
    }

    @Override
    public Sandbox create(final SandboxSpec spec) {
        final PodmanSandbox sandbox = new PodmanSandbox(spec, image);
        final ShellResult probe = sandbox.run("echo probe", PROBE_TIMEOUT, new CancelToken());
        if (!probe.succeeded()) {
            throw new IllegalStateException("The podman sandbox cannot run the image '" + image + "' on this host: " + probe.stderr().strip());
        }
        return sandbox;
    }
}
