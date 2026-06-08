package com.dmipi.coder.core.domain.shell;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The containment contract the core hands a provider: where commands run and may write, and the
 * time bounds. The provider turns this into an actual confinement; the contract is the core's,
 * not the provider's.
 */
public record SandboxSpec(Path projectDirectory, List<Path> additionalWritableDirectories, Duration defaultTimeout, Duration maxTimeout) {

    public SandboxSpec {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        additionalWritableDirectories = List.copyOf(Objects.requireNonNull(additionalWritableDirectories, "additionalWritableDirectories"));
        Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        Objects.requireNonNull(maxTimeout, "maxTimeout");
        if (defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("defaultTimeout must be positive.");
        }
        if (maxTimeout.compareTo(defaultTimeout) < 0) {
            throw new IllegalArgumentException("maxTimeout must be at least the default timeout.");
        }
    }
}
