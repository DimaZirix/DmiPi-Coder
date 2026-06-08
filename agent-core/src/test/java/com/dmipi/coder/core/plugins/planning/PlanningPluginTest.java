package com.dmipi.coder.core.plugins.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PlanningPluginTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);

    private final RecordingOut out = new RecordingOut();
    private final JacksonToolParamsParser parser = new JacksonToolParamsParser(JsonMapper.builder().build());
    private final TodoWriteTool tool = new TodoWriteTool();

    @Test
    @DisplayName("a todo_write call runs without asking and streams the list as a Todo display")
    void should_stream_the_task_list_without_asking() {
        // Given: the model writes a two-task list, then answers
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "todo_write",
                        "{\"todos\": [{\"content\": \"Read the file\", \"status\": \"completed\"}, {\"content\": \"Fix the bug\", \"status\": \"in_progress\"}]}"),
                ScriptedClient.textStep("on it")));
        final ScriptedHil hil = new ScriptedHil(List.of());

        // When
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(hil)
                .model(MODEL)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new PlanningPlugin())
                .build()) {
            coder.runTurn("fix the bug", new CancelToken());
        }

        // Then: no question was asked, and the display carries the list in order
        assertThat(hil.asked()).isEmpty();
        assertThat(out.events())
                .filteredOn(OutEvent.ActivityFinished.class::isInstance)
                .singleElement()
                .satisfies(event -> {
                    final Display display = ((OutEvent.ActivityFinished) event).display();
                    assertThat(display).isInstanceOf(Display.Todo.class);
                    assertThat(((Display.Todo) display).items()).containsExactly(
                            new Display.Todo.Item("Read the file", Display.Todo.Status.COMPLETED),
                            new Display.Todo.Item("Fix the bug", Display.Todo.Status.IN_PROGRESS));
                });
    }

    @Test
    @DisplayName("in plan mode the task list still works — planning is not a mutation")
    void should_run_in_plan_mode() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "todo_write",
                        "{\"todos\": [{\"content\": \"Outline the change\", \"status\": \"pending\"}]}"),
                ScriptedClient.textStep("here is the plan")));

        // When
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .mode(Mode.PLAN)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new PlanningPlugin())
                .build()) {
            coder.runTurn("plan it", new CancelToken());
        }

        // Then
        assertThat(out.kinds()).contains(OutEvent.ActivityFinished.class);
    }

    @Test
    @DisplayName("the plugin contributes a planning instruction section")
    void should_contribute_a_planning_instruction_section() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hello")));

        // When
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new PlanningPlugin())
                .build()) {
            coder.runTurn("hi", new CancelToken());
        }

        // Then: the system message the model saw carries the planning guidance
        assertThat(client.requests().getFirst().messages().getFirst().content()).contains("todo_write");
    }

    @Test
    @DisplayName("the tool is auto-allowed and reports a tally the model can read back")
    void should_report_a_tally_on_success() {
        // Given
        final ToolParams params = params(
                "{\"todos\": [{\"content\": \"a\", \"status\": \"completed\"}, {\"content\": \"b\", \"status\": \"in_progress\"}, {\"content\": \"c\", \"status\": \"pending\"}]}");

        // When
        final ToolResult result = tool.execute(params, new CancelToken());

        // Then
        assertThat(tool.defaultPermission(params)).isEqualTo(PermissionDecision.ALLOW);
        assertThat(result.llmContent()).isEqualTo("The task list now shows 3 tasks (1 completed, 1 in progress, 1 pending).");
        assertThat(tool.callSummary(params)).isEqualTo("3 tasks (1 completed, 1 in progress, 1 pending)");
    }

    @Test
    @DisplayName("an empty list is valid — it clears the display")
    void should_accept_an_empty_list() {
        // When
        final ToolResult result = tool.execute(params("{\"todos\": []}"), new CancelToken());

        // Then
        assertThat(result).isInstanceOf(ToolResult.Success.class);
        assertThat(((ToolResult.Success) result).display()).isEqualTo(new Display.Todo(List.of()));
    }

    @Test
    @DisplayName("malformed calls fail validation with a message the model can correct from")
    void should_reject_malformed_calls() {
        // When / Then
        assertThat(tool.validate(params("{}"))).hasValueSatisfying(error ->
                assertThat(error).contains("'todos'"));
        assertThat(tool.validate(params("{\"todos\": [{\"status\": \"pending\"}]}"))).hasValueSatisfying(error ->
                assertThat(error).contains("'content'"));
        assertThat(tool.validate(params("{\"todos\": [{\"content\": \"a\", \"status\": \"done\"}]}"))).hasValueSatisfying(error ->
                assertThat(error).contains("pending, in_progress or completed"));
        assertThat(tool.validate(params("{\"todos\": [{\"content\": \"a\", \"status\": \"pending\"}]}"))).isEmpty();
    }

    private ToolParams params(final String json) {
        return parser.parse(json);
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
