package com.dmipi.coder.core.domain.llm;

import java.util.Objects;

/** One tool invocation the model requested: its correlation id, the tool name, and the raw JSON arguments. */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A tool call requires a non-blank tool name.");
        }
    }
}
