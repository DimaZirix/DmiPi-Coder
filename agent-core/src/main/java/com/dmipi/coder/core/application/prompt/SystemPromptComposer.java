package com.dmipi.coder.core.application.prompt;

import java.util.List;
import java.util.Optional;

/**
 * Composes the system prompt from its slots — core instructions, then the conditional sandbox
 * and git sections, worked examples in the model's style, the environment facts, and finally
 * the plugin sections. The composition rules live here in the application layer; the
 * composition root only supplies the facts.
 */
public final class SystemPromptComposer {

    private SystemPromptComposer() {
    }

    /**
     * @param sandboxConfines present when a shell exists — true selects the inside-sandbox
     *        section, false the honest outside-sandbox one; empty adds no sandbox section
     * @param examplesStyleSuffix present when worked examples are enabled — the active model's
     *        prompt-style resource suffix; a style with no bundled resource falls back to the
     *        general workflow examples
     */
    public static String compose(
            final String coreInstructions,
            final Optional<Boolean> sandboxConfines,
            final boolean gitRepository,
            final Optional<String> examplesStyleSuffix,
            final Optional<EnvironmentFacts> environment,
            final List<String> pluginSections) {
        return new PromptAssembler()
                .add(coreInstructions)
                .add(sandboxConfines.map(confines -> PromptResources.load(confines ? "inside-sandbox.md" : "outside-sandbox.md")).orElse(""))
                .add(gitRepository ? PromptResources.load("git-repository.md") : "")
                .add(examplesStyleSuffix.map(SystemPromptComposer::examples).orElse(""))
                .add(environment.map(EnvironmentFacts::render).orElse(""))
                .addAll(pluginSections)
                .assemble();
    }

    private static String examples(final String styleSuffix) {
        final String styled = "examples-" + styleSuffix + ".md";
        return PromptResources.load(PromptResources.exists(styled) ? styled : "examples-general.md");
    }
}
