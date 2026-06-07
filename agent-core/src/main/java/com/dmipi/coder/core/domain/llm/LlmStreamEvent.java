package com.dmipi.coder.core.domain.llm;

import java.util.Objects;

/** What a model streams back: text, reasoning, tool-call fragments, and a finish signal. */
public sealed interface LlmStreamEvent {

    record TextDelta(String text) implements LlmStreamEvent {

        public TextDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    /** The model's reasoning stream — normalized by the core into transient thinking output. */
    record ThinkingDelta(String text) implements LlmStreamEvent {

        public ThinkingDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A fragment of a tool call; fragments with the same {@code index} belong to one call and
     * their {@code argumentsDelta} pieces concatenate. {@code id} and {@code name} may arrive
     * on the first fragment only — later fragments carry empty strings.
     */
    record ToolCallDelta(int index, String id, String name, String argumentsDelta) implements LlmStreamEvent {

        public ToolCallDelta {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(argumentsDelta, "argumentsDelta");
        }
    }

    record Finished(FinishReason reason) implements LlmStreamEvent {

        public Finished {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum FinishReason {
        STOP,
        TOOL_CALLS,
        LENGTH,
        OTHER
    }
}
