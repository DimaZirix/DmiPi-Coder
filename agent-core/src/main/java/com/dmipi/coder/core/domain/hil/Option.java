package com.dmipi.coder.core.domain.hil;

import java.util.Objects;

/**
 * One possible answer to a question: a stable id the answer refers to (never shown to the
 * user), a short label, and an optional one-line detail (empty when there is none).
 */
public record Option(String id, String label, String detail) {

    public Option {
        Objects.requireNonNull(detail, "detail");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("An option requires a non-blank id.");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("An option requires a non-blank label.");
        }
    }

    /** An option with no detail line. */
    public Option(final String id, final String label) {
        this(id, label, "");
    }
}
