package com.dmipi.coder.core.plugins.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.hil.Answer;
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

class PresentPlanTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);

    @Test
    @DisplayName("approving a presented plan switches the mode out of plan")
    void should_leave_plan_mode_on_approval() {
        // Given: plan mode; the model presents a plan; the user approves
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "present_plan", "{\"plan\": \"Edit A then run tests.\"}"),
                ScriptedClient.textStep("starting the work")));
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("approve")));

        try (Coder coder = coder(client, hil, Mode.PLAN)) {
            // When
            coder.runTurn("plan it", new CancelToken());

            // Then: mode is now default, and the plan was the HIL preview
            assertThat(coder.mode()).isEqualTo(Mode.DEFAULT);
            assertThat(hil.asked()).singleElement().satisfies(q -> assertThat(q.preview()).isEqualTo("Edit A then run tests."));
        }
    }

    @Test
    @DisplayName("keeping planning leaves plan mode in force")
    void should_stay_in_plan_mode_on_revise() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "present_plan", "{\"plan\": \"draft\"}"),
                ScriptedClient.textStep("revising")));
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("revise")));

        try (Coder coder = coder(client, hil, Mode.PLAN)) {
            // When
            coder.runTurn("plan it", new CancelToken());

            // Then
            assertThat(coder.mode()).isEqualTo(Mode.PLAN);
        }
    }

    private Coder coder(final ScriptedClient client, final ScriptedHil hil, final Mode mode) {
        return Coder.builder()
                .out(new RecordingOut())
                .hil(hil)
                .model(MODEL)
                .mode(mode)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new PlanningPlugin())
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
