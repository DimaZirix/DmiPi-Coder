package com.dmipi.coder.core.application.prompt;

import java.util.Objects;

/**
 * The static environment the model is told about: where it works, on what OS, which model, and
 * whether the project is a git repository. Deliberately excludes the date — that changes and
 * lives at the conversation tail so it does not invalidate the cached prompt prefix.
 */
public record EnvironmentFacts(String workingDirectory, String operatingSystem, String modelId, boolean gitRepository) {

    public EnvironmentFacts {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(operatingSystem, "operatingSystem");
        Objects.requireNonNull(modelId, "modelId");
    }

    /** The environment block for the system prompt. */
    public String render() {
        return """
                # Environment
                - Working directory: %s
                - Operating system: %s
                - Model: %s
                - Git repository: %s""".formatted(workingDirectory, operatingSystem, modelId, gitRepository ? "yes" : "no");
    }
}
