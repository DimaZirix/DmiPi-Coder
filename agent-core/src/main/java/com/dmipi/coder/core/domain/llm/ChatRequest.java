package com.dmipi.coder.core.domain.llm;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One model request: the messages so far, the tool schemas the model may call, and two optional
 * control switches — whether to suppress the model's thinking (control calls want a decision,
 * not deliberation), and a json-schema to constrain the reply to. The switches are advisory: a
 * provider ignores what its model or server does not support.
 */
public record ChatRequest(List<ChatMessage> messages, List<ToolSchema> tools, boolean thinkingDisabled, Optional<String> responseSchemaJson) {

    public ChatRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        Objects.requireNonNull(responseSchemaJson, "responseSchemaJson");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("A chat request requires at least one message.");
        }
    }

    public ChatRequest(final List<ChatMessage> messages, final List<ToolSchema> tools) {
        this(messages, tools, false, Optional.empty());
    }

    /** A control-call variant of this request: thinking off, constrained to the given schema. */
    public ChatRequest asControlCall(final String responseSchemaJson) {
        return new ChatRequest(messages, tools, true, Optional.of(responseSchemaJson));
    }
}
