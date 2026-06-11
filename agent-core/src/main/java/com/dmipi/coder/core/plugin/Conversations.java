package com.dmipi.coder.core.plugin;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.Tier;
import java.util.Objects;
import java.util.Optional;

/**
 * The conversations capability: run a nested agent conversation — a subagent — and get its
 * summary. The core supplies the machinery: the nested loop, the same permission gate, the
 * separate subagent output, and the inheritance rule (the subagent sees the tools of the
 * <em>other</em> plugins, never the declaring plugin's own, and never main-only tools).
 */
public interface Conversations {

    /** Runs the subagent to completion; returns its final message. Throws {@link IllegalStateException} when the nested turn fails. */
    String run(SubagentRequest request, CancelToken cancel);

    /** One delegation: the subagent's standing instructions, its task, its model preference and step budget. */
    record SubagentRequest(String instructions, String task, Optional<Tier> preferredTier, int maxSteps) {

        public SubagentRequest {
            Objects.requireNonNull(instructions, "instructions");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(preferredTier, "preferredTier");
            if (maxSteps <= 0) {
                throw new IllegalArgumentException("maxSteps must be positive.");
            }
        }
    }
}
