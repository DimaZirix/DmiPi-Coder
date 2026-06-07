package com.dmipi.coder.core.domain.hil;

import java.util.List;
import java.util.Objects;

/**
 * The user's selection for a question: the ids of the chosen options. Never empty — "none" is
 * an explicit option when it is a legitimate reply. Whether the selection fits the question
 * (shape, known ids) is judged by {@link Question#rejection}.
 */
public record Answer(List<String> selected) {

    public Answer {
        selected = List.copyOf(Objects.requireNonNull(selected, "selected"));
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("An answer requires at least one selected option id.");
        }
        if (selected.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("A selected option id must not be blank.");
        }
    }

    /** An answer selecting a single option. */
    public static Answer of(final String selectedId) {
        return new Answer(List.of(selectedId));
    }
}
