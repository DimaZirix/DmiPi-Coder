package com.dmipi.coder.core.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.PromptStyle;
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

class WorkedExamplesTest {

    @Test
    @DisplayName("the worked examples block is inserted into the system prompt")
    void should_add_the_examples_block() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        final String prompt = systemPrompt(client, new ModelDeclaration("m", "scripted", "", Tier.FAST, 8_000));

        // Then
        assertThat(prompt).contains("Examples (illustrating tone and workflow)").contains("Refactor the auth logic");
    }

    @Test
    @DisplayName("a style with no bundled resource falls back to the general workflow examples")
    void should_fall_back_to_general_for_styles_without_a_resource() {
        // Given: a qwen-coder model (no bundled qwen resource yet — awaits the fallback parser)
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));
        final ModelDeclaration qwen = new ModelDeclaration("q", "scripted", "", Tier.FAST, 8_000, PromptStyle.QWEN_CODER);

        // When
        final String prompt = systemPrompt(client, qwen);

        // Then
        assertThat(prompt).contains("Examples (illustrating tone and workflow)");
    }

    @Test
    @DisplayName("the default constructor keeps the GENERAL style")
    void should_default_to_general_style() {
        assertThat(new ModelDeclaration("m", "p", "e", Tier.FAST, 1).promptStyle()).isEqualTo(PromptStyle.GENERAL);
    }

    private String systemPrompt(final ScriptedClient client, final ModelDeclaration model) {
        try (Coder coder = Coder.builder()
                .out(new RecordingOut())
                .hil(new ScriptedHil(List.of()))
                .model(model)
                .workedExamples()
                .registerPlugin(providerPlugin(client))
                .build()) {
            coder.runTurn("go", new CancelToken());
        }
        return client.requests().getFirst().messages().getFirst().content();
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
