package com.dmipi.coder.core.plugin;

import java.util.Set;

/**
 * The universal plugin interface. A plugin lives in its own package, has no dependencies on and
 * no access to anything outside this interface, declares the capabilities it requires, and
 * contributes tools, instruction sections and providers through the registrar.
 */
public interface Plugin {

    /** The capabilities this plugin requires; it receives exactly these. */
    default Set<CapabilityType> requires() {
        return Set.of();
    }

    void install(PluginRegistrar registrar, Capabilities capabilities);
}
