package com.dmipi.coder.core.domain.llm;

import java.util.Objects;

/** A declared model resolved to its client. */
public record ConnectedModel(ModelDeclaration declaration, LlmClient client) {

    public ConnectedModel {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(client, "client");
    }
}
