package com.dmipi.coder.core.domain.llm;

import java.util.Objects;

/** One configured model: its name, the protocol it is reached by, the endpoint, its tier, its context window, and the tool-call style it speaks. */
public record ModelDeclaration(String name, String protocol, String endpoint, Tier tier, int contextWindow, PromptStyle promptStyle) {

    public ModelDeclaration {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(promptStyle, "promptStyle");
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

    /** A declaration with the default {@link PromptStyle#GENERAL} style. */
    public ModelDeclaration(final String name, final String protocol, final String endpoint, final Tier tier, final int contextWindow) {
        this(name, protocol, endpoint, tier, contextWindow, PromptStyle.GENERAL);
    }
}
