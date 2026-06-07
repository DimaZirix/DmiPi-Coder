package com.dmipi.coder.core.domain.permissions;

import java.util.Objects;

/** The gate's verdict for one call. */
public sealed interface GateDecision {

    record Allowed() implements GateDecision {
    }

    record Denied(String reason) implements GateDecision {

        public Denied {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
