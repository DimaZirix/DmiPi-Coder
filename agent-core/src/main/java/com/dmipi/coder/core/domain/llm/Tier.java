package com.dmipi.coder.core.domain.llm;

/**
 * A model's capability tier — an ordinal rank within the configured set, set by the operator.
 * Selection needs only the ordering: {@code FAST} is the cheapest, {@code STRONG} the most capable.
 */
public enum Tier {
    FAST,
    BALANCED,
    STRONG;

    public boolean atLeast(final Tier bar) {
        return ordinal() >= bar.ordinal();
    }
}
