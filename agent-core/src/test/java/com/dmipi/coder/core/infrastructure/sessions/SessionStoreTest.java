package com.dmipi.coder.core.infrastructure.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Role;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);

    @TempDir
    private Path projectDirectory;

    @Test
    @DisplayName("a saved session resumes in a new process: same dialogue, fresh instructions, model continues in context")
    void should_save_and_resume_a_conversation() {
        // Given: a first process talks, saves, and closes
        final ScriptedClient first = new ScriptedClient(List.of(ScriptedClient.textStep("the answer is 42")));
        try (Coder coder = coder(first)) {
            coder.runTurn("what is the answer?", new CancelToken());
            coder.saveSession("research");
            assertThat(coder.sessions()).containsExactly("research");
        }

        // When: a second process resumes and continues
        final ScriptedClient second = new ScriptedClient(List.of(ScriptedClient.textStep("as I said: 42")));
        try (Coder coder = coder(second)) {
            coder.resumeSession("research");
            coder.runTurn("remind me?", new CancelToken());
        }

        // Then: the resumed request carried the old dialogue under a fresh system message
        assertThat(second.requests().getFirst().messages().getFirst().role()).isEqualTo(Role.SYSTEM);
        assertThat(second.requests().getFirst().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("the answer is 42"));
    }

    @Test
    @DisplayName("resume refuses a conversation that already has history")
    void should_refuse_a_late_resume() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.textStep("hello"),
                ScriptedClient.textStep("unused")));
        try (Coder coder = coder(client)) {
            coder.saveSession("early");
            coder.runTurn("hi", new CancelToken());

            // When / Then
            assertThatIllegalStateException()
                    .isThrownBy(() -> coder.resumeSession("early"))
                    .withMessageContaining("history");
        }
    }

    @Test
    @DisplayName("without the grant, session calls fail loudly; bad names are refused")
    void should_guard_the_grant_and_the_names() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of());
        try (Coder ungranted = Coder.builder()
                .out(new RecordingOut())
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .projectDirectory(projectDirectory)
                .registerPlugin(providerPlugin(client))
                .build()) {

            // When / Then
            assertThatIllegalStateException()
                    .isThrownBy(() -> ungranted.saveSession("x"))
                    .withMessageContaining("enableSessions");
        }
        try (Coder granted = coder(client)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> granted.saveSession("../escape"))
                    .withMessageContaining("session name");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> granted.resumeSession("nope"))
                    .withMessageContaining("No saved session");
        }
    }

    private Coder coder(final ScriptedClient client) {
        return Coder.builder()
                .out(new RecordingOut())
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .projectDirectory(projectDirectory)
                .enableSessions()
                .registerPlugin(providerPlugin(client))
                .build();
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
