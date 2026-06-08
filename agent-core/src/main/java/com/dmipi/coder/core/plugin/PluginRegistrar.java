package com.dmipi.coder.core.plugin;

import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.permissions.PermissionPolicy;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.tool.Tool;

/**
 * The registration sink a plugin installs into. Registration order is the order the model sees
 * tools; a policy can only tighten the tool's own baseline.
 */
public interface PluginRegistrar {

    void registerTool(Tool tool);

    void registerTool(Tool tool, PermissionPolicy policy);

    /** Text composed into the system instructions at session start, after the core's own sections. */
    void registerInstructionSection(String section);

    /** A provider contribution: an LLM protocol implementation, matched to model declarations by name. */
    void registerProtocolProvider(ProtocolProvider provider);

    /** A provider contribution: a sandbox technology, matched to the configured technology name. */
    void registerSandboxProvider(SandboxProvider provider);
}
