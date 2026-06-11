package com.dmipi.coder.core.plugins.subagents;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.ChatRequest;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.llm.ToolSchema;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.plugins.files.FilesReadPlugin;
import com.dmipi.coder.core.plugins.planning.PlanningPlugin;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentsPluginTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);

    @TempDir
    private Path projectDirectory;

    private final RecordingOut out = new RecordingOut();
    private final RecordingOut subagentOut = new RecordingOut();

    @Test
    @DisplayName("a delegation runs a nested conversation and only its summary returns to the parent")
    void should_delegate_and_return_only_the_summary() {
        // Given: the model delegates, the subagent answers, the model wraps up
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "task", "{\"type\": \"explore\", \"instruction\": \"find where retries are configured\"}"),
                ScriptedClient.textStep("Findings: retries live in app.yaml under http.retries."),
                ScriptedClient.textStep("done")));

        // When
        runTurn(client);

        // Then: the parent read the summary as the tool result
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("Findings: retries live in app.yaml"));

        // And the subagent streamed apart — its words never entered the main out
        assertThat(subagentOut.answerText()).contains("Findings: retries live in app.yaml");
        assertThat(out.answerText()).isEqualTo("done");
    }

    @Test
    @DisplayName("the subagent inherits the other plugins' tools — never task itself, never main-only tools")
    void should_apply_the_inheritance_rule() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "task", "{\"type\": \"explore\", \"instruction\": \"look around\"}"),
                ScriptedClient.textStep("nothing found"),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client);

        // Then: the nested request offered the file tools, but neither delegation nor the todo list
        final ChatRequest nested = client.requests().get(1);
        assertThat(nested.tools()).extracting(ToolSchema::name)
                .contains("read_file", "grep_search")
                .doesNotContain("task", "todo_write");

        // And it ran under the explore instructions, not the main instructions
        assertThat(nested.messages().getFirst().content()).contains("exploration subagent");
    }

    @Test
    @DisplayName("an unknown type fails validation with the available types — the model can correct itself")
    void should_reject_an_unknown_type() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "task", "{\"type\": \"guess\", \"instruction\": \"x\"}"),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client);

        // Then
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("must be one of: explore, review"));
    }

    @Test
    @DisplayName("the task tool advertises its types in the description")
    void should_list_types_in_the_description() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        runTurn(client);

        // Then
        final ToolSchema task = client.requests().getFirst().tools().stream()
                .filter(schema -> schema.name().equals("task"))
                .findFirst()
                .orElseThrow();
        assertThat(task.description()).contains("- explore:").contains("- review:");
    }

    private void runTurn(final ScriptedClient client) {
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .projectDirectory(projectDirectory)
                .subagentOut(subagentOut)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new FilesReadPlugin())
                .registerPlugin(new PlanningPlugin())
                .registerPlugin(new SubagentsPlugin())
                .build()) {
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
