package com.dmipi.coder.core.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NextSpeakerCheckTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);

    private final RecordingOut out = new RecordingOut();

    @Test
    @DisplayName("a stalled step gets one nudge and the model finishes the work")
    void should_nudge_a_stalled_model_once() {
        // Given: the model stalls, the checker says "model", the model then finishes
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.textStep("I will now check the file."),
                ScriptedClient.textStep("model"),
                ScriptedClient.textStep(" All checked: it is fine.")));

        // When
        runTurn(client, true);

        // Then: the turn continued past the stall, on a nudge the model never wrote
        assertThat(out.answerText()).contains("All checked: it is fine.");
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("Continue and finish it now"));
    }

    @Test
    @DisplayName("when the checker says user, the turn ends")
    void should_end_the_turn_when_the_user_speaks_next() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.textStep("Done: the file is fixed."),
                ScriptedClient.textStep("user")));

        // When
        runTurn(client, true);

        // Then: two requests only — the step and the isolated check; no continuation
        assertThat(client.requests()).hasSize(2);
        assertThat(out.answerText()).isEqualTo("Done: the file is fixed.");

        // And the check was isolated — one message of data under its own instructions, not the conversation
        assertThat(client.requests().getLast().messages()).hasSize(2);
        assertThat(client.requests().getLast().messages().getLast().content()).isEqualTo("Done: the file is fixed.");
    }

    @Test
    @DisplayName("disabled by default: a text-only step just ends the turn")
    void should_stay_out_of_the_way_when_disabled() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("I will now check the file.")));

        // When
        runTurn(client, false);

        // Then
        assertThat(client.requests()).hasSize(1);
    }

    private void runTurn(final ScriptedClient client, final boolean enabled) {
        final Coder.Builder builder = Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .registerPlugin(providerPlugin(client));
        if (enabled) {
            builder.nextSpeakerCheck();
        }
        try (Coder coder = builder.build()) {
            coder.runTurn("go", new CancelToken());
        }
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
