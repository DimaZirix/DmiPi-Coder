package com.dmipi.coder.core.plugins.podman;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;

/**
 * Contributes the {@code podman} sandbox provider — container confinement. Like every sandbox
 * provider it is trusted computing base: configured explicitly (Builder.sandbox("podman")),
 * never auto-selected. Supply an image whose filesystem carries the project's toolchain.
 */
public final class PodmanSandboxPlugin implements Plugin {

    private final String image;

    /** Uses {@link PodmanSandboxProvider#DEFAULT_IMAGE}. */
    public PodmanSandboxPlugin() {
        this(PodmanSandboxProvider.DEFAULT_IMAGE);
    }

    public PodmanSandboxPlugin(final String image) {
        this.image = image;
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerSandboxProvider(new PodmanSandboxProvider(image));
    }
}
