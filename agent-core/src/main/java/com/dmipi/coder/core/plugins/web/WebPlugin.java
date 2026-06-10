package com.dmipi.coder.core.plugins.web;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.util.Set;

/** The web tool: web_fetch — a guarded fetch whose result is an isolated summary, never the raw page. */
public final class WebPlugin implements Plugin {

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.HTTP, CapabilityType.LLM);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerTool(new WebFetchTool(capabilities.http(), capabilities.llms()));
    }
}
