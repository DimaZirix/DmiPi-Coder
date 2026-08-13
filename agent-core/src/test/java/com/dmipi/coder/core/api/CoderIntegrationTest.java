package com.dmipi.coder.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.LlmStreamEvent;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Role;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.plugin.Tools;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import com.dmipi.coder.core.testfixtures.StubTool;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoderIntegrationTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("local", "scripted", "", Tier.FAST, 8_000);

    private final RecordingOut out = new RecordingOut();

    @Test
    @DisplayName("a full turn: the plugin's tool is gated through HIL, executes, and the model answers")
    void should_run_a_full_gated_turn() {
        // Given: a plugin contributing a provider, an ASK-gated tool and an instruction section
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.toolCallStep("c1", "echo", "{\"text\": \"hi\"}"), ScriptedClient.textStep("done")));
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("allow-once")));
        final Coder coder = Coder.builder()
                .instructions("CORE INSTRUCTIONS")
                .out(out)
                .hil(hil)
                .model(MODEL)
                .registerPlugin(toolPlugin(client, PermissionDecision.ASK))
                .build();

        // When
        coder.runTurn("say hi", new CancelToken());

        // Then: the gate asked, the tool ran, the answer streamed, and the plugin section reached the system message
        assertThat(hil.asked()).singleElement().satisfies(question -> assertThat(question.question()).contains("echo"));
        assertThat(out.kinds()).containsExactly(OutEvent.TurnStarted.class, OutEvent.ActivityStarted.class, OutEvent.ActivityFinished.class, OutEvent.AnswerDelta.class, OutEvent.TurnEnded.class);
        assertThat(out.answerText()).isEqualTo("done");
        assertThat(client.requests().getFirst().messages().getFirst())
                .satisfies(system -> {
                    assertThat(system.role()).isEqualTo(Role.SYSTEM);
                    assertThat(system.content()).isEqualTo("CORE INSTRUCTIONS\n\nPLUGIN SECTION");
                });
    }

    @Test
    @DisplayName("plugin code invokes another plugin's tool by name through the Tools capability, gate included")
    void should_invoke_a_tool_by_name_from_plugin_code() {
        // Given: plugin A holds the Tools capability; plugin B contributes the tool
        final ToolsHolder holder = new ToolsHolder();
        Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .registerPlugin(toolPlugin(new ScriptedClient(List.of()), PermissionDecision.ALLOW))
                .registerPlugin(holder)
                .build();

        // When
        final ToolResult result = holder.tools.invoke("echo", "{\"text\": \"from code\"}", new CancelToken());

        // Then
        assertThat(result).isInstanceOf(ToolResult.Success.class);
        assertThat(result.llmContent()).isEqualTo("echo: from code");

        // When: an absent tool is invoked
        final ToolResult absent = holder.tools.invoke("no-such-tool", "{}", new CancelToken());

        // Then
        assertThat(absent).isInstanceOf(ToolResult.Failure.class);
        assertThat(absent.llmContent()).contains("no-such-tool");
    }

    @Test
    @DisplayName("accessing an undeclared capability fails loudly at install")
    void should_fail_on_an_undeclared_capability() {
        // Given: a plugin that touches HIL without declaring it
        final Plugin greedy = new Plugin() {

            @Override
            public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
                capabilities.hil();
            }
        };

        // When / Then
        assertThatIllegalStateException()
                .isThrownBy(() -> Coder.builder().out(out).hil(new ScriptedHil(List.of())).model(MODEL).registerPlugin(toolPlugin(new ScriptedClient(List.of()), PermissionDecision.ALLOW)).registerPlugin(greedy).build())
                .withMessageContaining("HIL");
    }

    @Test
    @DisplayName("the facade exposes models and modes: list, active, activate, switch")
    void should_expose_models_and_modes() {
        // Given
        final Coder coder = Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .registerPlugin(toolPlugin(new ScriptedClient(List.of()), PermissionDecision.ALLOW))
                .build();

        // Then
        assertThat(coder.models()).hasSize(1);
        assertThat(coder.activeModel().name()).isEqualTo("local");
        assertThat(coder.mode()).isEqualTo(Mode.DEFAULT);

        // When
        coder.switchMode(Mode.PLAN);

        // Then
        assertThat(coder.mode()).isEqualTo(Mode.PLAN);
    }

    @Test
    @DisplayName("a cancel during one tool call stops the rest of the step — no post-cancel prompts, no post-cancel execution")
    void should_not_gate_or_run_tool_calls_after_a_cancel() {
        // Given: one step requesting two calls; the first cancels the turn, the second would ask
        final CancelToken token = new CancelToken();
        final boolean[] secondRan = {false};
        final ScriptedClient client = new ScriptedClient(List.of(List.of(
                new LlmStreamEvent.ToolCallDelta(0, "c1", "trigger_cancel", "{}"),
                new LlmStreamEvent.ToolCallDelta(1, "c2", "second", "{}"),
                new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.TOOL_CALLS))));
        final ScriptedHil hil = new ScriptedHil(List.of());
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(hil)
                .model(MODEL)
                .registerPlugin(toolPlugin(client, PermissionDecision.ALLOW))
                .registerPlugin(new Plugin() {

                    @Override
                    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
                        registrar.registerTool(new StubTool("trigger_cancel", ToolKind.READ, PermissionDecision.ALLOW, params -> {
                            token.cancel();
                            return new ToolResult.Success("cancelling", new Display.Text("cancelled"));
                        }));
                        registrar.registerTool(new StubTool("second", ToolKind.EXECUTE, PermissionDecision.ASK, params -> {
                            secondRan[0] = true;
                            return new ToolResult.Success("ran", new Display.Text("ran"));
                        }));
                    }
                })
                .build()) {

            // When
            coder.runTurn("go", token);
        }

        // Then: the second call neither asked (ScriptedHil would have thrown) nor ran
        assertThat(hil.asked()).isEmpty();
        assertThat(secondRan[0]).isFalse();
    }

    /** Contributes the scripted protocol provider, an echo tool with the given baseline, and a section. */
    private static Plugin toolPlugin(final ScriptedClient client, final PermissionDecision echoBaseline) {
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
                registrar.registerTool(new StubTool("echo", ToolKind.READ, echoBaseline, params -> new ToolResult.Success("echo: " + params.string("text").orElse(""), new Display.Text("echoed"))));
                registrar.registerInstructionSection("PLUGIN SECTION");
            }
        };
    }

    /** A plugin that only holds the Tools capability for later use from code. */
    private static final class ToolsHolder implements Plugin {

        private Tools tools;

        @Override
        public Set<CapabilityType> requires() {
            return Set.of(CapabilityType.TOOLS);
        }

        @Override
        public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
            this.tools = capabilities.tools();
        }
    }
}
