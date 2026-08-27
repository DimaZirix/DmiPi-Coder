package com.dmipi.coder.core.domain.shell;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The containment contract the core hands a provider: where commands run and may write, the
 * time bounds, and the resolved network. The provider turns this into an actual confinement;
 * the contract is the core's, not the provider's.
 */
public record SandboxSpec(Path projectDirectory, List<Path> additionalWritableDirectories, Duration defaultTimeout, Duration maxTimeout, SandboxNetwork network) {

    public SandboxSpec {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        additionalWritableDirectories = List.copyOf(Objects.requireNonNull(additionalWritableDirectories, "additionalWritableDirectories"));
        Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        Objects.requireNonNull(maxTimeout, "maxTimeout");
        Objects.requireNonNull(network, "network");
        if (defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("defaultTimeout must be positive.");
        }
        if (maxTimeout.compareTo(defaultTimeout) < 0) {
            throw new IllegalArgumentException("maxTimeout must be at least the default timeout.");
        }
    }

    /** The contract with the network open — the default until egress control is configured. */
    public SandboxSpec(final Path projectDirectory, final List<Path> additionalWritableDirectories, final Duration defaultTimeout, final Duration maxTimeout) {
        this(projectDirectory, additionalWritableDirectories, defaultTimeout, maxTimeout, new SandboxNetwork.Open());
    }

    /** The same contract with the network resolved — the core fills in the proxy once it is running. */
    public SandboxSpec withNetwork(final SandboxNetwork resolved) {
        return new SandboxSpec(projectDirectory, additionalWritableDirectories, defaultTimeout, maxTimeout, resolved);
    }
}
