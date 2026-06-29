package com.dmipi.coder.core.plugins.sandbox;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import com.dmipi.coder.core.infrastructure.shell.ProcessRunner;
import java.time.Duration;

/**
 * Direct execution on the host: the system shell in the project directory, no confinement.
 * What it does bound: the timeout, cancellation, and captured output ({@link ProcessRunner}).
 */
final class DirectSandbox implements Sandbox {

    private final SandboxSpec spec;

    DirectSandbox(final SandboxSpec spec) {
        this.spec = spec;
    }

    @Override
    public ShellResult run(final String command, final Duration timeout, final CancelToken cancel) {
        return ProcessRunner.run(ProcessRunner.systemShell(command), spec.projectDirectory(), timeout, cancel);
    }

    @Override
    public Process startBackground(final String command) {
        return ProcessRunner.start(ProcessRunner.systemShell(command), spec.projectDirectory());
    }

    @Override
    public String technology() {
        return DirectSandboxProvider.TECHNOLOGY;
    }

    @Override
    public boolean confines() {
        return false;
    }

    @Override
    public void close() {
    }
}
