package com.dmipi.coder.core.plugins.subagents;

import com.dmipi.coder.core.domain.llm.Tier;
import java.util.Objects;
import java.util.Optional;

/**
 * One delegation type — content, like a skill: what it is good for (the tool description
 * advertises it), the instructions the subagent runs under, its preferred model tier, and its
 * step budget.
 */
public record SubagentType(String name, String description, String instructions, Optional<Tier> preferredTier, int maxSteps) {

    public SubagentType {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(preferredTier, "preferredTier");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive.");
        }
    }
}
