package com.dmipi.coder.core.plugins.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.llm.ToolSchema;
import com.dmipi.coder.core.plugins.sandbox.DirectSandboxPlugin;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShellPluginTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);

    private final RecordingOut out = new RecordingOut();

    @Test
    @DisplayName("with a sandbox provider, the shell tool is registered and gated as an EXECUTE (ASK) tool")
    void should_register_the_shell_tool_with_a_sandbox_provider() {
        // Given: the model tries a shell command, then answers; the user denies the command
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "run_shell_command", "{\"command\": \"echo hi\"}"),
                ScriptedClient.textStep("ok")));
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("deny")));

        // When
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(hil)
                .model(MODEL)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new DirectSandboxPlugin())
                .registerPlugin(new ShellPlugin())
                .build()) {
            coder.runTurn("run it", new CancelToken());
        }

        // Then: the tool exists, and the permission question previewed the exact command
        assertThat(client.requests().getFirst().tools()).extracting(ToolSchema::name).contains("run_shell_command");
        assertThat(hil.asked()).singleElement().satisfies(question -> {
            assertThat(question.question()).contains("run_shell_command");
            assertThat(question.preview()).isEqualTo("echo hi");
        });
    }

    @Test
    @DisplayName("the shell plugin without a sandbox provider fails the build with a clear message")
    void should_fail_without_a_sandbox_provider() {
        // When / Then
        assertThatIllegalStateException()
                .isThrownBy(() -> Coder.builder()
                        .out(out)
                        .hil(new ScriptedHil(List.of()))
                        .model(MODEL)
                        .registerPlugin(providerPlugin(new ScriptedClient(List.of())))
                        .registerPlugin(new ShellPlugin())
                        .build())
                .withMessageContaining("sandbox provider");
    }

    @Test
    @DisplayName("selecting an unregistered technology fails the build")
    void should_fail_for_an_unknown_technology() {
        // When / Then
        assertThatIllegalStateException()
                .isThrownBy(() -> Coder.builder()
                        .out(out)
                        .hil(new ScriptedHil(List.of()))
                        .model(MODEL)
                        .sandbox("bubblewrap")
                        .registerPlugin(providerPlugin(new ScriptedClient(List.of())))
                        .registerPlugin(new DirectSandboxPlugin())
                        .registerPlugin(new ShellPlugin())
                        .build())
                .withMessageContaining("bubblewrap");
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
