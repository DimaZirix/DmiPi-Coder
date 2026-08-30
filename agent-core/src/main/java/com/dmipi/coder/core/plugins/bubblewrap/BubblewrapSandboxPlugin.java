package com.dmipi.coder.core.plugins.bubblewrap;

import com.dmipi.coder.core.domain.shell.ResourceLimits;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;

/**
 * Contributes the {@code bubblewrap} sandbox provider — filesystem confinement via unprivileged
 * namespaces, optionally resource-bounded via {@code systemd-run}. Like every sandbox provider
 * it is trusted computing base: configured explicitly (Builder.sandbox("bubblewrap")), never
 * auto-selected.
 */
public final class BubblewrapSandboxPlugin implements Plugin {

    private final ResourceLimits limits;

    /** Uses {@link ResourceLimits#none()}: filesystem confinement only. */
    public BubblewrapSandboxPlugin() {
        this(ResourceLimits.none());
    }

    public BubblewrapSandboxPlugin(final ResourceLimits limits) {
        this.limits = limits;
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerSandboxProvider(new BubblewrapSandboxProvider(limits));
    }
}
