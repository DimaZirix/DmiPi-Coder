package com.dmipi.coder.core.domain.tool;

/** Parses a tool call's raw JSON arguments into params. */
public interface ToolParamsParser {

    /**
     * The parsed params. Invalid JSON or a non-object raises {@link IllegalArgumentException}
     * with a message the model can correct from — the loop feeds it back as the tool result.
     */
    ToolParams parse(String argumentsJson);
}
