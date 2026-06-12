package com.dmipi.coder.core.application.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Composes the system prompt from ordered sections. Sections are appended in slot order — the
 * caller decides the order (core sections, conditional sections, worked examples, environment,
 * then plugin sections last) — and blank sections are dropped. The assembled text joins the
 * kept sections with a blank line between them.
 */
public final class PromptAssembler {

    private static final String SEPARATOR = "\n\n";

    private final List<String> sections = new ArrayList<>();

    /** Appends one section; a null or blank section is ignored so empty slots leave no trace. */
    public PromptAssembler add(final String section) {
        if (section != null && !section.isBlank()) {
            sections.add(section);
        }
        return this;
    }

    /** Appends each section in order, dropping blanks. */
    public PromptAssembler addAll(final List<String> more) {
        Objects.requireNonNull(more, "more").forEach(this::add);
        return this;
    }

    public String assemble() {
        return String.join(SEPARATOR, sections);
    }
}
