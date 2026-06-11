package com.dmipi.coder.core.infrastructure.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsLoaderTest {

    @TempDir
    private Path userDirectory;

    @TempDir
    private Path projectDirectory;

    @Test
    @DisplayName("a missing settings file is empty settings")
    void should_treat_a_missing_file_as_empty() {
        // When / Then
        assertThat(SettingsLoader.load(userDirectory)).isEqualTo(Settings.empty());
    }

    @Test
    @DisplayName("models, mode, sandbox and shell timeouts load from the settings file")
    void should_load_every_settings_part() throws IOException {
        // Given
        writeSettings(userDirectory, """
                {
                  "models": [{"name": "local", "protocol": "scripted", "endpoint": "http://localhost:1234/v1", "tier": "fast", "contextWindow": 32000}],
                  "mode": "allow_edits",
                  "sandbox": {"technology": "direct", "additionalWritableDirectories": ["/var/cache/builds"]},
                  "shell": {"defaultTimeoutSeconds": 30, "maxTimeoutSeconds": 300}
                }""");

        // When
        final Settings settings = SettingsLoader.load(userDirectory);

        // Then
        assertThat(settings.models()).containsExactly(new ModelDeclaration("local", "scripted", "http://localhost:1234/v1", Tier.FAST, 32_000));
        assertThat(settings.mode()).hasValue(Mode.ALLOW_EDITS);
        assertThat(settings.sandboxTechnology()).hasValue("direct");
        assertThat(settings.additionalWritableDirectories()).containsExactly(Path.of("/var/cache/builds"));
        assertThat(settings.shellDefaultTimeout()).hasValue(java.time.Duration.ofSeconds(30));
        assertThat(settings.shellMaxTimeout()).hasValue(java.time.Duration.ofSeconds(300));
    }

    @Test
    @DisplayName("a malformed file or unknown enum fails loudly, naming the file")
    void should_fail_loudly_on_bad_settings() throws IOException {
        // Given
        writeSettings(userDirectory, "{not json");
        writeSettings(projectDirectory, "{\"mode\": \"yolo\"}");

        // When / Then
        assertThatIllegalStateException()
                .isThrownBy(() -> SettingsLoader.load(userDirectory))
                .withMessageContaining("settings.json");
        assertThatIllegalStateException()
                .isThrownBy(() -> SettingsLoader.load(projectDirectory))
                .withMessageContaining("yolo");
    }

    @Test
    @DisplayName("through the builder: user settings apply first, project settings win where both speak")
    void should_apply_settings_with_project_precedence() throws IOException {
        // Given: user declares a model and default mode; project re-declares the model and the mode
        writeSettings(userDirectory, """
                {"models": [{"name": "local", "protocol": "scripted", "endpoint": "http://user", "tier": "fast", "contextWindow": 8000}],
                 "mode": "default"}""");
        writeSettings(projectDirectory, """
                {"models": [{"name": "local", "protocol": "scripted", "endpoint": "http://project", "tier": "strong", "contextWindow": 16000}],
                 "mode": "plan"}""");

        // When
        try (Coder coder = Coder.builder()
                .out(new RecordingOut())
                .hil(new ScriptedHil(List.of()))
                .userDirectory(userDirectory)
                .projectDirectory(projectDirectory)
                .loadUserSettings()
                .loadProjectSettings()
                .registerPlugin(providerPlugin(new ScriptedClient(List.of())))
                .build()) {

            // Then
            assertThat(coder.models()).containsExactly(new ModelDeclaration("local", "scripted", "http://project", Tier.STRONG, 16_000));
            assertThat(coder.mode()).isEqualTo(Mode.PLAN);
        }
    }

    private static void writeSettings(final Path anchor, final String json) throws IOException {
        Files.createDirectories(anchor.resolve(".coder"));
        Files.writeString(anchor.resolve(".coder/settings.json"), json);
    }

    private static Plugin providerPlugin(final ScriptedClient client) {
        return new Plugin() {

            @Override
            public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
                registrar.registerProtocolProvider(new ProtocolProvider() {

                    @Override
                    public String protocol() {
                        return "scripted";
                    }

                    @Override
                    public LlmClient connect(final ModelDeclaration declaration) {
                        return client;
                    }
                });
            }
        };
    }
}
