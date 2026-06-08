package com.dmipi.coder.core.plugins.bubblewrap;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;

/**
 * Contributes the {@code bubblewrap} sandbox provider — filesystem confinement via unprivileged
 * namespaces. Like every sandbox provider it is trusted computing base: configured explicitly
 * (Builder.sandbox("bubblewrap")), never auto-selected.
 */
public final class BubblewrapSandboxPlugin implements Plugin {

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerSandboxProvider(new BubblewrapSandboxProvider());
    }
}
