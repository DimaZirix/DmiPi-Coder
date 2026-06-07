package com.dmipi.coder.core.plugins.openai;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;

/**
 * The built-in OpenAI-compatible protocol provider: any model declared with
 * {@code protocol: "openai"} is reached as {@code <endpoint>/chat/completions} with streaming.
 * An ordinary provider plugin — the same contract anyone would implement for another protocol.
 */
public final class OpenAiProviderPlugin implements Plugin {

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerProtocolProvider(new OpenAiProtocolProvider());
    }
}
