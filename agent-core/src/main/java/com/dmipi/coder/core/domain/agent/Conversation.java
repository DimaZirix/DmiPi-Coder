package com.dmipi.coder.core.domain.agent;

import com.dmipi.coder.core.domain.llm.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The one shared history: the system instructions first (never saved with a session — rebuilt
 * fresh), then prompts, the agent's words, tool calls and results, in order.
 *
 * <p>Not thread-safe — confined to the conversation's driving thread.
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

    /** Replaces the system instructions at index 0 — used to replay a saved prompt verbatim on resume. */
    public void replaceSystemInstructions(final String systemInstructions) {
        messages.set(0, ChatMessage.system(Objects.requireNonNull(systemInstructions, "systemInstructions")));
    }

    /**
     * Replaces the history before {@code keepFromIndex} with the given summary message; the
     * system instructions and the tail from that index stay as they are.
     */
    public void compact(final int keepFromIndex, final ChatMessage summary) {
        if (keepFromIndex < 1 || keepFromIndex > messages.size()) {
            throw new IllegalArgumentException("keepFromIndex must be within the history, got " + keepFromIndex + ".");
        }
        final List<ChatMessage> kept = new ArrayList<>(messages.subList(keepFromIndex, messages.size()));
        final ChatMessage instructions = messages.getFirst();
        messages.clear();
        messages.add(instructions);
        messages.add(Objects.requireNonNull(summary, "summary"));
        messages.addAll(kept);
    }
}
