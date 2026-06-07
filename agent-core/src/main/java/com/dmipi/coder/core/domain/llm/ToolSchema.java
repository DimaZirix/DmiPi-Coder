package com.dmipi.coder.core.domain.llm;

import java.util.Objects;

/** What the model is told about one tool: name, description, and the raw JSON parameter schema. */
public record ToolSchema(String name, String description, String parametersJson) {

    public ToolSchema {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJson, "parametersJson");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A tool schema requires a non-blank name.");
        }
    }
}
