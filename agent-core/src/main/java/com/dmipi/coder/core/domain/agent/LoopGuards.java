package com.dmipi.coder.core.domain.agent;

import java.util.Optional;

/**
 * The optional services the fully-wired main loop consults: keep the conversation inside the
 * window ({@link ContextManager}), nudge a stalled step ({@link NextSpeakerCheck}), and append
 * transient reminders ({@link Reminders}). A component may be absent (null) — consumers reach
 * them only through the Optional {@code wired*} accessors, so no null check leaks into the
 * loop. A subagent or a bare loop uses {@link #none()}.
 */
public record LoopGuards(ContextManager contextManager, NextSpeakerCheck nextSpeaker, Reminders reminders) {

    private static final LoopGuards NONE = new LoopGuards(null, null, null);

    public static LoopGuards none() {
        return NONE;
    }

    public Optional<ContextManager> wiredContextManager() {
        return Optional.ofNullable(contextManager);
    }

    public Optional<NextSpeakerCheck> wiredNextSpeaker() {
        return Optional.ofNullable(nextSpeaker);
    }

    public Optional<Reminders> wiredReminders() {
        return Optional.ofNullable(reminders);
    }
}
