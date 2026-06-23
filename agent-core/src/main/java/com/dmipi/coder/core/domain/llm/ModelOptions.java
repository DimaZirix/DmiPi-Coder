package com.dmipi.coder.core.domain.llm;

import java.util.Objects;
import java.util.Optional;

/**
 * The optional per-model wire settings, all with sensible defaults so a bare declaration still
 * works: the tool-call style, the idle-stream timeout (a very large default — local models are
 * slow, and it only bounds total silence, not total response time), the env var holding an API
 * key, whether the model thinks on the main conversation, and structured-output support.
 */
public record ModelOptions(PromptStyle promptStyle, int idleTimeoutSeconds, Optional<String> apiKeyEnv, boolean thinking, StructuredOutput structuredOutput) {

    private static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 900;

    public ModelOptions {
        Objects.requireNonNull(promptStyle, "promptStyle");
        Objects.requireNonNull(apiKeyEnv, "apiKeyEnv");
        Objects.requireNonNull(structuredOutput, "structuredOutput");
        if (idleTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("idleTimeoutSeconds must be positive, got " + idleTimeoutSeconds + ".");
        }
    }

    public static ModelOptions defaults() {
        return new ModelOptions(PromptStyle.GENERAL, DEFAULT_IDLE_TIMEOUT_SECONDS, Optional.empty(), true, StructuredOutput.AUTO);
    }

    public ModelOptions withPromptStyle(final PromptStyle style) {
        return new ModelOptions(style, idleTimeoutSeconds, apiKeyEnv, thinking, structuredOutput);
    }
}
