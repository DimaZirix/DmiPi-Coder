package com.dmipi.coder.core.application.prompt;

import java.util.List;

/**
 * The bundled core system-prompt sections — persona and mandates, careful execution, untrusted
 * content, and a closing reminder. Not wired by default (the empty-core guarantee): an embedder
 * opts in by passing {@link #standard()} to {@code Coder.Builder.instructions(...)}, so a core
 * with no front-end has no persona.
 */
public final class CorePrompt {

    private static final List<String> SECTIONS = List.of(
            "core-system-prompt.md",
            "executing-with-care.md",
            "untrusted-content.md",
            "final-reminder.md");

    private CorePrompt() {
    }

    /** The standard core sections, assembled in order. */
    public static String standard() {
        final PromptAssembler assembler = new PromptAssembler();
        SECTIONS.forEach(name -> assembler.add(PromptResources.load(name)));
        return assembler.assemble();
    }
}
