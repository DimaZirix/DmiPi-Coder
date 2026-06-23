package com.dmipi.coder.core.domain.llm;

import java.util.Objects;

/** One configured model: name, protocol, endpoint, tier, context window, and optional wire {@link ModelOptions}. */
public record ModelDeclaration(String name, String protocol, String endpoint, Tier tier, int contextWindow, ModelOptions options) {

    public ModelDeclaration {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(options, "options");
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

    /** A declaration with default {@link ModelOptions}. */
    public ModelDeclaration(final String name, final String protocol, final String endpoint, final Tier tier, final int contextWindow) {
        this(name, protocol, endpoint, tier, contextWindow, ModelOptions.defaults());
    }

    /** A declaration with a chosen prompt style, otherwise default options. */
    public ModelDeclaration(final String name, final String protocol, final String endpoint, final Tier tier, final int contextWindow, final PromptStyle promptStyle) {
        this(name, protocol, endpoint, tier, contextWindow, ModelOptions.defaults().withPromptStyle(promptStyle));
    }

    public PromptStyle promptStyle() {
        return options.promptStyle();
    }
}
