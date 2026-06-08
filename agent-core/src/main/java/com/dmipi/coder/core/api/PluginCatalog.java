package com.dmipi.coder.core.api;

import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.permissions.PermissionPolicy;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Collects what the plugins contribute; the builder turns it into registries after installation. */
final class PluginCatalog implements PluginRegistrar {

    private final List<Tool> tools = new ArrayList<>();
    private final Map<Tool, PermissionPolicy> policies = new IdentityHashMap<>();
    private final List<String> instructionSections = new ArrayList<>();
    private final List<ProtocolProvider> protocolProviders = new ArrayList<>();
    private final List<SandboxProvider> sandboxProviders = new ArrayList<>();

    @Override
    public void registerTool(final Tool tool) {
        tools.add(Objects.requireNonNull(tool, "tool"));
    }

    @Override
    public void registerTool(final Tool tool, final PermissionPolicy policy) {
        registerTool(tool);
        policies.put(tool, Objects.requireNonNull(policy, "policy"));
    }

    @Override
    public void registerInstructionSection(final String section) {
        instructionSections.add(Objects.requireNonNull(section, "section"));
    }

    @Override
    public void registerProtocolProvider(final ProtocolProvider provider) {
        protocolProviders.add(Objects.requireNonNull(provider, "provider"));
    }

    @Override
    public void registerSandboxProvider(final SandboxProvider provider) {
        sandboxProviders.add(Objects.requireNonNull(provider, "provider"));
    }

    List<Tool> tools() {
        return List.copyOf(tools);
    }

    Map<Tool, PermissionPolicy> policies() {
        return Map.copyOf(policies);
    }

    List<String> instructionSections() {
        return List.copyOf(instructionSections);
    }

    List<ProtocolProvider> protocolProviders() {
        return List.copyOf(protocolProviders);
    }

    List<SandboxProvider> sandboxProviders() {
        return List.copyOf(sandboxProviders);
    }
}
