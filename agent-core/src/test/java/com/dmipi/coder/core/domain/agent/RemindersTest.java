package com.dmipi.coder.core.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RemindersTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);

    @Test
    @DisplayName("the reminder rides the request but is never written to the durable history")
    void should_append_to_the_request_but_not_the_history() {
        // Given: reminders every step
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("done")));

        // When
        final ScriptedClient run = runTurn(client, Mode.DEFAULT);

        // Then: the outbound user prompt carried the date reminder and the rules refresher
        final String userMessage = run.requests().getFirst().messages().getLast().content();
        assertThat(userMessage)
                .contains("please help")
                .contains("<system-reminder>")
                .contains("Current date:")
                .contains("Keep these rules in force");

        // And a second turn's stored history shows the prompt without the reminder text
        final ScriptedClient second = new ScriptedClient(List.of(ScriptedClient.textStep("a"), ScriptedClient.textStep("b")));
        try (Coder coder = builder(second, Mode.DEFAULT).build()) {
            coder.runTurn("first", new com.dmipi.coder.core.domain.agent.CancelToken());
            coder.runTurn("second", new com.dmipi.coder.core.domain.agent.CancelToken());
            // the second request's history contains the first prompt verbatim (no reminder baked in)
            assertThat(second.requests().getLast().messages())
                    .anySatisfy(message -> assertThat(message.content()).isEqualTo("first"));
        }
    }

    @Test
    @DisplayName("plan mode adds its notice to the reminder")
    void should_add_the_plan_notice_in_plan_mode() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("ok")));

        // When
        final ScriptedClient run = runTurn(client, Mode.PLAN);

        // Then
        assertThat(run.requests().getFirst().messages().getLast().content()).contains("Plan mode is active");
    }

    @Test
    @DisplayName("without the grant, no reminder is appended")
    void should_stay_silent_without_the_grant() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("ok")));

        // When
        try (Coder coder = Coder.builder()
                .out(new RecordingOut())
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .registerPlugin(providerPlugin(client))
                .build()) {
            coder.runTurn("please help", new CancelToken());
        }

        // Then
        assertThat(client.requests().getFirst().messages().getLast().content()).isEqualTo("please help").doesNotContain("system-reminder");
    }

    private ScriptedClient runTurn(final ScriptedClient client, final Mode mode) {
        try (Coder coder = builder(client, mode).build()) {
            coder.runTurn("please help", new CancelToken());
        }
        return client;
    }

    private Coder.Builder builder(final ScriptedClient client, final Mode mode) {
        return Coder.builder()
                .out(new RecordingOut())
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .mode(mode)
                .reminders()
                .reminderInterval(1)
                .registerPlugin(providerPlugin(client));
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
