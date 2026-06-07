package com.dmipi.coder.core.domain.permissions;

/**
 * The approval mode: what happens to a call whose composed decision is ASK. A mode never
 * overrides an explicit DENY or a hard limit — it only decides the ask outcome.
 */
public enum Mode {

    /** Ask the user. */
    DEFAULT,

    /** Read-only research: every mutating call is blocked until the plan is approved. */
    PLAN,

    /** File edits auto-approved; everything else still asks. */
    ALLOW_EDITS,

    /** Everything runs without asking. */
    ALLOW_ALL,

    /** Never prompts: anything that would ask is blocked instead — the headless-safe mode. */
    DONT_ASK;

    /** The outcome for a call that would ask. */
    public AskOutcome askOutcome() {
        return switch (this) {
            case ALLOW_ALL -> AskOutcome.RUN;
            case DONT_ASK -> AskOutcome.BLOCK;
            case DEFAULT, PLAN, ALLOW_EDITS -> AskOutcome.PROMPT;
        };
    }

    public enum AskOutcome {
        RUN,
        PROMPT,
        BLOCK
    }
}
