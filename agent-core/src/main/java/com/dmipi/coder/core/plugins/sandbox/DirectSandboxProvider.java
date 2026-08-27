package com.dmipi.coder.core.plugins.sandbox;

import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxNetwork;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.shell.SandboxSpec;

/**
 * The honest no-confinement provider: runs commands directly on the host, in the project
 * directory. Surfaced to the user as degraded — it does not confine — and the fallback on hosts
 * with no real sandbox technology.
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
    public boolean confines() {
        return false;
    }

    @Override
    public Sandbox create(final SandboxSpec spec) {
        // Honesty over convenience: silently running with the network open would pretend a contract this provider cannot enforce.
        if (!(spec.network() instanceof SandboxNetwork.Open)) {
            throw new IllegalStateException("The direct sandbox does not confine, so it cannot enforce the requested '" + spec.network().getClass().getSimpleName() + "' network. Configure a confining technology or leave the network open.");
        }
        return new DirectSandbox(spec);
    }
}
