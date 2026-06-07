package com.dmipi.coder.core.domain.tool;

import com.dmipi.coder.core.domain.event.Display;
import java.util.Objects;

/** What a tool execution produced: content for the model, and — on success — a display for the user. */
public sealed interface ToolResult {

    /** What the model reads as the tool's result. */
    String llmContent();

    record Success(String llmContent, Display display) implements ToolResult {

        public Success {
            Objects.requireNonNull(llmContent, "llmContent");
            Objects.requireNonNull(display, "display");
        }
    }

    record Failure(String llmContent) implements ToolResult {

        public Failure {
            Objects.requireNonNull(llmContent, "llmContent");
        }
    }
}
