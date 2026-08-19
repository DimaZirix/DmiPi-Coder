package com.dmipi.coder.core.plugins.bubblewrap;

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
 * The bubblewrap containment technology, with optional resource bounds ({@link ResourceLimits})
 * enforced through {@code systemd-run --user --scope}. {@link #create} runs the conformance probe
 * from the spec before handing the sandbox out: a trivial command must run, and a write outside
 * the allowed paths must fail — a misconfigured or lying confinement is refused before any real
 * command runs.
 */
public final class BubblewrapSandboxProvider implements SandboxProvider {

    static final String TECHNOLOGY = "bubblewrap";

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final String PROBE_FILE_NAME = ".dmipi-coder-probe";

    /** Weak probe target: a non-root user cannot write there even unconfined, but it exists on every host. */
    private static final Path FALLBACK_OUTSIDE_PATH = Path.of("/usr");

    private final ResourceLimits limits;

    /** Filesystem confinement only, no resource bounds. */
    public BubblewrapSandboxProvider() {
        this(ResourceLimits.none());
    }

    public BubblewrapSandboxProvider(final ResourceLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public String technology() {
        return TECHNOLOGY;
    }

    @Override
    public boolean available() {
        return onPath("bwrap") && (!limits.bounded() || onPath("systemd-run"));
    }

    private static boolean onPath(final String executable) {
        return Stream.of(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry).resolve(executable))
                .anyMatch(Files::isExecutable);
    }

    @Override
    public boolean confines() {
        return true;
    }

    @Override
    public Sandbox create(final SandboxSpec spec) {
        final BubblewrapSandbox sandbox = new BubblewrapSandbox(spec, limits);
        probe(sandbox, spec);
        return sandbox;
    }

    private static void probe(final Sandbox sandbox, final SandboxSpec spec) {
        final ShellResult trivial = sandbox.run("echo probe", PROBE_TIMEOUT, new CancelToken());
        if (!trivial.succeeded()) {
            throw new IllegalStateException("The bubblewrap sandbox cannot run commands on this host: " + trivial.stderr().strip());
        }

        final Path probeFile = outsideWritablePath(spec).resolve(PROBE_FILE_NAME);
        final String outsideWrite = "touch '" + probeFile + "' && rm -f '" + probeFile + "'";
        if (sandbox.run(outsideWrite, PROBE_TIMEOUT, new CancelToken()).succeeded()) {
            throw new IllegalStateException("The bubblewrap sandbox failed its conformance probe: a write outside the allowed paths (" + probeFile + ") succeeded.");
        }
    }

    /**
     * The user's home is the probe target whenever it can serve: the host user may write there,
     * so only real confinement makes the write fail — a lying sandbox is caught. When home is
     * itself inside an allowed path (or not writable on the host) the probe falls back to
     * {@link #FALLBACK_OUTSIDE_PATH}.
     */
    private static Path outsideWritablePath(final SandboxSpec spec) {
        final Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        final boolean insideAllowed = Stream.concat(Stream.of(spec.projectDirectory()), spec.additionalWritableDirectories().stream())
                .map(allowed -> allowed.toAbsolutePath().normalize())
                .anyMatch(home::startsWith);
        if (insideAllowed || !Files.isWritable(home)) {
            return FALLBACK_OUTSIDE_PATH;
        }
        return home;
    }
}
