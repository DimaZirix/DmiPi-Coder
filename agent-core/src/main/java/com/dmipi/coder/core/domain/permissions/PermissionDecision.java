package com.dmipi.coder.core.domain.permissions;

/** A verdict about one call, ordered by strictness: composition can only tighten. */
public enum PermissionDecision {
    ALLOW,
    ASK,
    DENY;

    /** The stricter of the two — the only way decisions compose. */
    public PermissionDecision tightenedBy(final PermissionDecision other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}
