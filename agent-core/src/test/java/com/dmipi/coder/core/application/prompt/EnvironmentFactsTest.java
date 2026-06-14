package com.dmipi.coder.core.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
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

class EnvironmentFactsTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("coder-local", "scripted", "", Tier.FAST, 8_000);

    @Test
    @DisplayName("explicit facts render a block with cwd, OS, model and git, and no date")
    void should_render_explicit_facts() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));
        final EnvironmentFacts facts = new EnvironmentFacts("/work/proj", "Linux", "coder-local", true);

        // When
        final String prompt = systemPromptWith(client, builder -> builder.environment(facts));

        // Then
        assertThat(prompt)
                .contains("# Environment")
                .contains("Working directory: /work/proj")
                .contains("Operating system: Linux")
                .contains("Model: coder-local")
                .contains("Git repository: yes");
        assertThat(facts.render()).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    @DisplayName("the gather grant fills the block from the real host and active model")
    void should_gather_from_the_host() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        final String prompt = systemPromptWith(client, Coder.Builder::gatherEnvironment);

        // Then
        assertThat(prompt).contains("# Environment").contains("Model: coder-local");
    }

    @Test
    @DisplayName("without the grant, no environment block appears")
    void should_omit_without_the_grant() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        final String prompt = systemPromptWith(client, builder -> builder);

        // Then
        assertThat(prompt).doesNotContain("# Environment");
    }

    private String systemPromptWith(final ScriptedClient client, final java.util.function.UnaryOperator<Coder.Builder> configure) {
        final Coder.Builder builder = Coder.builder()
                .out(new RecordingOut())
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .registerPlugin(providerPlugin(client));
        try (Coder coder = configure.apply(builder).build()) {
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
