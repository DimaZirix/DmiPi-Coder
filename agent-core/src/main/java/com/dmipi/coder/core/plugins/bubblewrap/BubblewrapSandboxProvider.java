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
import java.util.stream.Stream;

/**
 * The bubblewrap containment technology. {@link #create} runs the conformance probe from the
 * spec before handing the sandbox out: a trivial command must run, and a write outside the
 * allowed paths must fail — a misconfigured or lying confinement is refused before any real
 * command runs.
 */
public final class BubblewrapSandboxProvider implements SandboxProvider {

    static final String TECHNOLOGY = "bubblewrap";

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final String OUTSIDE_WRITE = "touch /usr/.dmipi-coder-probe && rm -f /usr/.dmipi-coder-probe";

    @Override
    public String technology() {
        return TECHNOLOGY;
    }

    @Override
    public boolean available() {
        return Stream.of(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry).resolve("bwrap"))
                .anyMatch(Files::isExecutable);
    }

    @Override
    public Sandbox create(final SandboxSpec spec) {
        final BubblewrapSandbox sandbox = new BubblewrapSandbox(spec);
        probe(sandbox);
        return sandbox;
    }

    private static void probe(final Sandbox sandbox) {
        final ShellResult trivial = sandbox.run("echo probe", PROBE_TIMEOUT, new CancelToken());
        if (!trivial.succeeded()) {
            throw new IllegalStateException("The bubblewrap sandbox cannot run commands on this host: " + trivial.stderr().strip());
        }
        if (sandbox.run(OUTSIDE_WRITE, PROBE_TIMEOUT, new CancelToken()).succeeded()) {
            throw new IllegalStateException("The bubblewrap sandbox failed its conformance probe: a write outside the allowed paths succeeded.");
        }
    }
}
