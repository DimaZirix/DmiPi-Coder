package com.dmipi.coder.core.infrastructure.settings;

import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.permissions.PermissionRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the conventional settings file {@code .coder/settings.json} under an anchor. A missing
 * file is empty settings; a malformed file or value fails loudly — a half-read configuration is
 * worse than a clear startup error.
 */
public final class SettingsLoader {

    private static final String SETTINGS_LOCATION = ".coder/settings.json";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SettingsLoader() {
    }

    public static Settings load(final Path anchor) {
        final Path file = anchor.resolve(SETTINGS_LOCATION);
        if (!Files.isRegularFile(file)) {
            return Settings.empty();
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Could not read the settings file " + file + ": " + unreadable.getMessage(), unreadable);
        } catch (final JacksonException malformed) {
            throw new IllegalStateException("The settings file " + file + " is not valid JSON: " + malformed.getMessage());
        }
        return parse(root, file);
    }

    private static Settings parse(final JsonNode root, final Path file) {
        final List<ModelDeclaration> models = new ArrayList<>();
        for (final JsonNode model : root.path("models")) {
            models.add(model(model, file));
        }
        final JsonNode sandbox = root.path("sandbox");
        final List<Path> writable = new ArrayList<>();
        for (final JsonNode directory : sandbox.path("additionalWritableDirectories")) {
            writable.add(Path.of(directory.stringValue()));
        }
        final JsonNode shell = root.path("shell");
        final List<PermissionRule> permissionRules = new ArrayList<>();
        for (final JsonNode rule : root.path("permissions")) {
            permissionRules.add(permissionRule(rule, file));
        }
        return new Settings(
                models,
                text(root, "mode").map(value -> parsed(value, Mode.class, file)),
                text(sandbox, "technology"),
                writable,
                seconds(shell, "defaultTimeoutSeconds", file),
                seconds(shell, "maxTimeoutSeconds", file),
                permissionRules);
    }

    private static PermissionRule permissionRule(final JsonNode rule, final Path file) {
        try {
            return new PermissionRule(
                    text(rule, "tool").orElse("*"),
                    text(rule, "argument").orElse(""),
                    parsed(text(rule, "decision").orElse(""), PermissionDecision.class, file));
        } catch (final IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid permission rule in " + file + ": " + invalid.getMessage());
        }
    }

    private static ModelDeclaration model(final JsonNode model, final Path file) {
        try {
            return new ModelDeclaration(
                    text(model, "name").orElse(""),
                    text(model, "protocol").orElse(""),
                    text(model, "endpoint").orElse(""),
                    parsed(text(model, "tier").orElse(""), Tier.class, file),
                    model.path("contextWindow").asInt(0));
        } catch (final IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid model declaration in " + file + ": " + invalid.getMessage());
        }
    }

    private static <E extends Enum<E>> E parsed(final String value, final Class<E> type, final Path file) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            throw new IllegalStateException("The settings file " + file + " names an unknown " + type.getSimpleName().toLowerCase(Locale.ROOT) + " '" + value + "'.");
        }
    }

    private static Optional<String> text(final JsonNode node, final String key) {
        final JsonNode value = node.path(key);
        return value.isString() && !value.stringValue().isBlank() ? Optional.of(value.stringValue()) : Optional.empty();
    }

    private static Optional<Duration> seconds(final JsonNode node, final String key, final Path file) {
        final JsonNode value = node.path(key);
        if (value.isMissingNode()) {
            return Optional.empty();
        }
        if (!value.isIntegralNumber() || value.longValue() <= 0) {
            throw new IllegalStateException("The settings file " + file + " needs a positive integer for '" + key + "'.");
        }
        return Optional.of(Duration.ofSeconds(value.longValue()));
    }
}
