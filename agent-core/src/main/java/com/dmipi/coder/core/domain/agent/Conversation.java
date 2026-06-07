package com.dmipi.coder.core.domain.agent;

import com.dmipi.coder.core.domain.llm.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The one shared history: the system instructions first (never saved with a session — rebuilt
 * fresh), then prompts, the agent's words, tool calls and results, in order.
 */
public final class Conversation {

    private final List<ChatMessage> messages = new ArrayList<>();

    public Conversation(final String systemInstructions) {
        messages.add(ChatMessage.system(Objects.requireNonNull(systemInstructions, "systemInstructions")));
    }

    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public void add(final ChatMessage message) {
        messages.add(Objects.requireNonNull(message, "message"));
    }
}
