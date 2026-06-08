package com.dmipi.coder.core.plugins.shell;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.time.Duration;

/**
 * The honest no-confinement provider: runs commands directly on the host, in the project
 * directory. Surfaced to the user as degraded — it does not confine — and the fallback on hosts
 * with no real sandbox technology.
 *
 * <p>Skeleton: the actual process execution is deferred to the second step; {@link #create}
 * returns a sandbox whose {@code run} is not yet implemented.
 */
public final class DirectSandboxProvider implements SandboxProvider {

    static final String TECHNOLOGY = "direct";

    @Override
    public String technology() {
        return TECHNOLOGY;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Sandbox create(final SandboxSpec spec) {
        return new DirectSandbox(spec);
    }

    private record DirectSandbox(SandboxSpec spec) implements Sandbox {

        @Override
        public ShellResult run(final String command, final Duration timeout, final CancelToken cancel) {
            throw new UnsupportedOperationException("Shell execution is not implemented yet (step 2).");
        }

        @Override
        public String technology() {
            return TECHNOLOGY;
        }

        @Override
        public boolean confines() {
            return false;
        }

        @Override
        public void close() {
        }
    }
}
