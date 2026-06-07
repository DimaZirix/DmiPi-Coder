package com.dmipi.coder.core.domain.llm;

import java.util.List;
import java.util.Objects;

/** One model request: the messages so far, plus the tool schemas the model may call. */
public record ChatRequest(List<ChatMessage> messages, List<ToolSchema> tools) {

    public ChatRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("A chat request requires at least one message.");
        }
    }
}
