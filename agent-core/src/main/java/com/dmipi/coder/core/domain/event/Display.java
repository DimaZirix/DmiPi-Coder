package com.dmipi.coder.core.domain.event;

import java.util.Objects;

/**
 * The display payload of a finished action — what a front-end shows for it. Rendered by the
 * front-end in its own style; the core never sends presentation.
 */
public sealed interface Display {

    /** A one-line or short-text result ("read 120 lines", "fetched the page"). */
    record Text(String text) implements Display {

        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A file change, as a unified diff. */
    record Diff(String unifiedDiff) implements Display {

        public Diff {
            Objects.requireNonNull(unifiedDiff, "unifiedDiff");
        }
    }
}
