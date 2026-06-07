package com.dmipi.coder.core.domain.llm;

import java.util.Objects;

/** One configured model: its name, the protocol it is reached by, the endpoint, its tier, and its context window. */
public record ModelDeclaration(String name, String protocol, String endpoint, Tier tier, int contextWindow) {

    public ModelDeclaration {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(tier, "tier");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A model declaration requires a non-blank name.");
        }
        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("Model '" + name + "' requires a non-blank protocol.");
        }
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("Model '" + name + "' requires a positive context window, got " + contextWindow + ".");
        }
    }
}
