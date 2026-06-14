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

    /** True when this technology actually confines commands; false for the honest {@code direct} no-op. Known before a sandbox is built, so the prompt can tell the model the truth. */
    boolean confines();

    Sandbox create(SandboxSpec spec);
}
