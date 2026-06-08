package com.dmipi.coder.core.domain.event;

import java.util.List;
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

    /** The agent's task list, replacing whatever was shown before; items keep the given order. */
    record Todo(List<Item> items) implements Display {

        public Todo {
            items = List.copyOf(items);
        }

        public enum Status {
            PENDING,
            IN_PROGRESS,
            COMPLETED
        }

        /** One task on the list. */
        public record Item(String text, Status status) {

            public Item {
                Objects.requireNonNull(text, "text");
                Objects.requireNonNull(status, "status");
            }
        }
    }
}
