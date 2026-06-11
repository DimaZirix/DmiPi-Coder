package com.dmipi.coder.core.plugins.subagents;

import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Contributes the {@code task} delegation tool with the built-in types: {@code explore} (fast
 * tier, read-around-and-report) and {@code review} (strong tier, judge code). Types are content
 * — construct with your own list to ship specialized ones; an empty list registers no tool.
 */
public final class SubagentsPlugin implements Plugin {

    private static final String SUMMARY_CONTRACT = " Your final message is all the caller ever sees — make it complete and self-contained.";
    private static final List<SubagentType> BUILT_IN = List.of(
            new SubagentType(
                    "explore",
                    "Finds things in the codebase — where something is configured, how a subsystem hangs together. Good for broad searches that would flood the main context.",
                    "You are an exploration subagent inside a coding agent. Investigate exactly what the task asks, using the read and search tools; do not modify anything. Report your findings with concrete file paths and line references." + SUMMARY_CONTRACT,
                    Optional.of(Tier.FAST),
                    15),
            new SubagentType(
                    "review",
                    "Reviews code or a change for correctness and quality; returns concrete findings.",
                    "You are a code-review subagent inside a coding agent. Read the code the task points at and judge it: correctness first, then clarity and design. Report concrete findings with file paths, each with a short why." + SUMMARY_CONTRACT,
                    Optional.of(Tier.STRONG),
                    15));

    private final List<SubagentType> types;

    public SubagentsPlugin() {
        this(BUILT_IN);
    }

    public SubagentsPlugin(final List<SubagentType> types) {
        this.types = List.copyOf(types);
    }

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.CONVERSATIONS);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        if (!types.isEmpty()) {
            registrar.registerTool(new TaskTool(capabilities.conversations(), types));
        }
    }
}
