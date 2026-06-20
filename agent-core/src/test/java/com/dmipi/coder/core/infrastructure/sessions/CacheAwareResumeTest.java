package com.dmipi.coder.core.infrastructure.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.api.ResumeResult;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.plugins.planning.PlanningPlugin;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheAwareResumeTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("coder-local", "scripted", "", Tier.FAST, 8_000);

    @TempDir
    private Path projectDirectory;

    @Test
    @DisplayName("same world: resume replays the saved prompt byte-for-byte so the request prefix is identical")
    void should_reuse_the_prompt_when_the_world_matches() {
        // Given: a first process with a core prompt, saves after a turn
        final ScriptedClient first = new ScriptedClient(List.of(ScriptedClient.textStep("the answer is 42")));
        final String savedRequestPrompt;
        try (Coder coder = coder(first, "CORE PROMPT")) {
            coder.runTurn("what is the answer?", new CancelToken());
            savedRequestPrompt = first.requests().getFirst().messages().getFirst().content();
            coder.saveSession("research");
        }

        // When: a second process with the same setup resumes
        final ScriptedClient second = new ScriptedClient(List.of(ScriptedClient.textStep("still 42")));
        try (Coder coder = coder(second, "CORE PROMPT")) {
            final ResumeResult result = coder.resumeSession("research");
            coder.runTurn("again?", new CancelToken());

            // Then: the prompt was reused, and the resumed request's system message equals the saved one
            assertThat(result).isEqualTo(ResumeResult.PROMPT_REUSED);
            assertThat(second.requests().getFirst().messages().getFirst().content()).isEqualTo(savedRequestPrompt);
        }
    }

    @Test
    @DisplayName("changed tools: resume rebuilds the prompt and reports the cache is cold")
    void should_rebuild_when_the_tool_set_changed() {
        // Given: a first process WITH the planning plugin (a todo_write tool), saves
        final ScriptedClient first = new ScriptedClient(List.of(ScriptedClient.textStep("noted")));
        try (Coder coder = Coder.builder()
                .out(new RecordingOut()).hil(new ScriptedHil(List.of())).model(MODEL)
                .instructions("CORE PROMPT").projectDirectory(projectDirectory).enableSessions()
                .registerPlugin(providerPlugin(first)).registerPlugin(new PlanningPlugin())
                .build()) {
            coder.runTurn("note it", new CancelToken());
            coder.saveSession("work");
        }

        // When: a second process WITHOUT the planning plugin resumes (tool set differs)
        final ScriptedClient second = new ScriptedClient(List.of(ScriptedClient.textStep("ok")));
        try (Coder coder = coder(second, "CORE PROMPT")) {
            final ResumeResult result = coder.resumeSession("work");

            // Then
            assertThat(result).isEqualTo(ResumeResult.PROMPT_REBUILT);
        }
    }

    private Coder coder(final ScriptedClient client, final String prompt) {
        return Coder.builder()
                .out(new RecordingOut())
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .instructions(prompt)
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
