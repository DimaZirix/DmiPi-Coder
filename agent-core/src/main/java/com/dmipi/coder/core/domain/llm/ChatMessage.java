package com.dmipi.coder.core.domain.llm;

import java.util.List;
import java.util.Objects;

/**
 * One entry of the conversation history. {@code toolCalls} is non-empty only on an assistant
 * message that requested tools; {@code toolCallId} is non-empty only on a tool-result message.
 */
public record ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(toolCallId, "toolCallId");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        if (!toolCalls.isEmpty() && role != Role.ASSISTANT) {
            throw new IllegalArgumentException("Only an assistant message carries tool calls, got role " + role + " with " + toolCalls.size() + ".");
        }
        if (!toolCallId.isBlank() && role != Role.TOOL) {
            throw new IllegalArgumentException("Only a tool-result message carries a toolCallId, got role " + role + " with '" + toolCallId + "'.");
        }
        if (role == Role.TOOL && toolCallId.isBlank()) {
            throw new IllegalArgumentException("A tool-result message requires the toolCallId it answers.");
        }
    }

    public static ChatMessage system(final String content) {
        return new ChatMessage(Role.SYSTEM, content, List.of(), "");
    }

    public static ChatMessage user(final String content) {
        return new ChatMessage(Role.USER, content, List.of(), "");
    }

    public static ChatMessage assistant(final String content) {
        return new ChatMessage(Role.ASSISTANT, content, List.of(), "");
    }

    public static ChatMessage assistant(final String content, final List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, toolCalls, "");
    }

    public static ChatMessage toolResult(final String toolCallId, final String content) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId);
    }
}
