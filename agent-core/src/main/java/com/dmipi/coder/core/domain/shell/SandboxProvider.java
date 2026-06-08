package com.dmipi.coder.core.domain.shell;

/**
 * A provider contribution: implements one containment technology. Contributed by plugins and
 * matched to the configured technology name by the core. A sandbox provider is part of the
 * trusted computing base — it *is* the confinement — so it is configured explicitly, never
 * auto-discovered.
 */
public interface SandboxProvider {

    /** The technology name configuration selects by, e.g. {@code direct} or {@code bubblewrap}. */
    String technology();

    /** True when this technology is available on the host (e.g. its binary is installed). */
    boolean available();

    Sandbox create(SandboxSpec spec);
}
