package com.dmipi.coder.core.domain.agent;

/**
 * The optional services the fully-wired main loop consults, each nullable: keep the conversation
 * inside the window ({@link ContextManager}), nudge a stalled step ({@link NextSpeakerCheck}),
 * and append transient reminders ({@link Reminders}). A subagent or a bare loop uses
 * {@link #none()}.
 */
public record LoopGuards(ContextManager contextManager, NextSpeakerCheck nextSpeaker, Reminders reminders) {

    private static final LoopGuards NONE = new LoopGuards(null, null, null);

    public static LoopGuards none() {
        return NONE;
    }
}
