package com.dmipi.coder.core.plugins.sandbox;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;

/**
 * Contributes the {@code direct} sandbox provider — the honest no-confinement fallback. A real
 * confining technology (e.g. bubblewrap) ships as its own provider plugin and is configured
 * explicitly; sandbox providers are part of the trusted computing base.
 */
public final class DirectSandboxPlugin implements Plugin {

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerSandboxProvider(new DirectSandboxProvider());
    }
}
