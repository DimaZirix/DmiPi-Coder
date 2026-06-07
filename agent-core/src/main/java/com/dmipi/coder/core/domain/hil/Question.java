package com.dmipi.coder.core.domain.hil;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A HIL question: one line of plain language, an optional verbatim preview of what is at stake
 * (a diff, a command line — empty when there is nothing to show), the answer shape, and the
 * closed set of options. The asker defines the options and interprets the answer; "other" or
 * "none" is modelled as an explicit option when it is a legitimate reply.
 */
public record Question(String question, String preview, QuestionKind kind, List<Option> options) {

    private static final int MINIMUM_OPTIONS = 2;

    public Question {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(kind, "kind");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("A question requires a non-blank question text.");
        }
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (options.size() < MINIMUM_OPTIONS) {
            throw new IllegalArgumentException("A question requires at least " + MINIMUM_OPTIONS + " options, got " + options.size() + ".");
        }
        if (optionIds(options).size() != options.size()) {
            throw new IllegalArgumentException("Option ids must be unique within a question.");
        }
    }

    /**
     * Why the answer is not acceptable for this question, or empty when it is. The core rejects
     * any answer this method rejects, so askers can rely on the closed set.
     */
    public Optional<String> rejection(final Answer answer) {
        final List<String> selected = answer.selected();
        if (new HashSet<>(selected).size() != selected.size()) {
            return Optional.of("The same option was selected more than once.");
        }

        final Set<String> known = optionIds(options);
        for (final String id : selected) {
            if (!known.contains(id)) {
                return Optional.of("Unknown option id '" + id + "'.");
            }
        }

        if (kind == QuestionKind.OPTION_LIST && selected.size() != 1) {
            return Optional.of("An option-list question requires exactly one selection, got " + selected.size() + ".");
        }
        return Optional.empty();
    }

    private static Set<String> optionIds(final List<Option> options) {
        return options.stream()
                .map(Option::id)
                .collect(Collectors.toSet());
    }
}
