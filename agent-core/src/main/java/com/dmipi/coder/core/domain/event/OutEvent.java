package com.dmipi.coder.core.domain.event;

import java.util.Objects;

/**
 * The out-event vocabulary. Front-ends filter or style by type; hiding a type entirely (e.g.
 * thinking) is correct. Thinking is display-only and transient: never saved in sessions, never
 * re-sent to the model.
 */
public sealed interface OutEvent {

    /** The model's reasoning stream, when the model produces one. */
    record ThinkingDelta(String text) implements OutEvent {

        public ThinkingDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    /** The agent's actual words to the user, streamed as they are produced. */
    record AnswerDelta(String text) implements OutEvent {

        public AnswerDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    /** An action began; {@code summary} is one line of what it targets (a path, a command…). */
    record ActivityStarted(String action, String summary) implements OutEvent {

        public ActivityStarted {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(summary, "summary");
        }
    }

    /** An action completed, with its display payload. */
    record ActivityFinished(String action, Display display) implements OutEvent {

        public ActivityFinished {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(display, "display");
        }
    }

    /** An action failed; the turn may still continue (the model sees the error and can react). */
    record ActivityFailed(String action, String error) implements OutEvent {

        public ActivityFailed {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(error, "error");
        }
    }

    /** A turn began. */
    record TurnStarted() implements OutEvent {
    }

    /** A turn ended normally — answered, handed back, step limit, or cancelled. */
    record TurnEnded() implements OutEvent {
    }

    /** A turn aborted with an error; the conversation stays usable. */
    record TurnFailed(String error) implements OutEvent {

        public TurnFailed {
            Objects.requireNonNull(error, "error");
        }
    }

    /** Housekeeping: the older history was compacted into a summary. */
    record ContextCompacted(int approxTokensBefore, int approxTokensAfter) implements OutEvent {
    }
}
