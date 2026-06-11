package com.dmipi.coder.core.infrastructure.settings;

import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.permissions.Mode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** What one settings file declared; every part is optional — absent parts change nothing. */
public record Settings(
        List<ModelDeclaration> models,
        Optional<Mode> mode,
        Optional<String> sandboxTechnology,
        List<Path> additionalWritableDirectories,
        Optional<Duration> shellDefaultTimeout,
        Optional<Duration> shellMaxTimeout) {

    public Settings {
        models = List.copyOf(models);
        additionalWritableDirectories = List.copyOf(additionalWritableDirectories);
    }

    public static Settings empty() {
        return new Settings(List.of(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }
}
