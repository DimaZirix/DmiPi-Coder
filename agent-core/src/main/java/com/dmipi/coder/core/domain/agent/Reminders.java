package com.dmipi.coder.core.domain.agent;

import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.Role;
import com.dmipi.coder.core.domain.permissions.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Transient reminders appended to the tail of a request, never written to the durable history:
 * the current date (the one volatile environment fact, kept out of the cached prompt prefix), a
 * plan-mode notice while it holds, and a periodic critical-rules refresher. They ride the last
 * user/assistant message — not a mid-step tool result — so the cached prefix stays intact until
 * a genuine turn boundary.
 */
public final class Reminders {

    private final int interval;
    private final String rulesRefresher;
    private final String planNotice;
    private final Supplier<Mode> mode;
    private final Supplier<String> today;

    public Reminders(final int interval, final String rulesRefresher, final String planNotice, final Supplier<Mode> mode, final Supplier<String> today) {
        if (interval <= 0) {
            throw new IllegalArgumentException("The reminder interval must be positive, got " + interval + ".");
        }
        this.interval = interval;
        this.rulesRefresher = java.util.Objects.requireNonNull(rulesRefresher, "rulesRefresher");
        this.planNotice = java.util.Objects.requireNonNull(planNotice, "planNotice");
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
        this.today = java.util.Objects.requireNonNull(today, "today");
    }

    /**
     * The request messages with reminders appended to the last message, when that message is a
     * user/assistant boundary. Returns the input unchanged mid-step (a trailing tool result) or
     * when there is nothing to remind.
     */
    public List<ChatMessage> applyTo(final List<ChatMessage> messages, final int step) {
        if (messages.isEmpty()) {
            return messages;
        }
        final ChatMessage last = messages.getLast();
        if (last.role() != Role.USER && last.role() != Role.ASSISTANT) {
            return messages;
        }
        final String reminder = compose(step);
        if (reminder.isBlank()) {
            return messages;
        }
        final List<ChatMessage> augmented = new ArrayList<>(messages);
        augmented.set(augmented.size() - 1, new ChatMessage(last.role(), last.content() + "\n\n" + reminder, last.toolCalls(), last.toolCallId()));
        return augmented;
    }

    private String compose(final int step) {
        final List<String> parts = new ArrayList<>();
        parts.add("<system-reminder>\nCurrent date: " + today.get() + "\n</system-reminder>");
        if (mode.get() == Mode.PLAN) {
            parts.add(planNotice);
        }
        if (step % interval == 0) {
            parts.add(rulesRefresher);
        }
        return String.join("\n\n", parts);
    }
}
