package com.dmipi.coder.core.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.llm.LlmStreamEvent;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ModelRegistry;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Role;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.permissions.GateDecision;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ToolGate;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolRegistry;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.StubTool;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentLoopTest {

    private static final ToolGate ALLOW_ALL_GATE = (tool, params) -> new GateDecision.Allowed();

    private final RecordingOut out = new RecordingOut();
    private final Conversation conversation = new Conversation("system");

    @Test
    @DisplayName("a plain answer streams to out and ends the turn explicitly")
    void should_stream_a_plain_answer() {
        // Given
        final AgentLoop loop = loop(new ScriptedClient(List.of(ScriptedClient.textStep("Hello!"))), List.of());

        // When
        loop.runTurn("hi", new CancelToken());

        // Then
        assertThat(out.answerText()).isEqualTo("Hello!");
        assertThat(out.kinds()).containsExactly(OutEvent.TurnStarted.class, OutEvent.AnswerDelta.class, OutEvent.TurnEnded.class);
        assertThat(conversation.messages()).hasSize(3);
    }

    @Test
    @DisplayName("a tool call executes with activity events, its result returns to the model, the model answers")
    void should_execute_a_tool_call_and_continue() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.toolCallStep("c1", "echo", "{\"text\": \"hi\"}"), ScriptedClient.textStep("done")));
        final StubTool echo = new StubTool("echo", ToolKind.READ, PermissionDecision.ALLOW, params -> new ToolResult.Success("echo: " + params.string("text").orElse(""), new Display.Text("echoed")));
        final AgentLoop loop = loop(client, List.of(echo));

        // When
        loop.runTurn("say hi", new CancelToken());

        // Then: the tool ran, its result went back to the model, and the turn finished with an answer
        assertThat(out.kinds()).containsExactly(OutEvent.TurnStarted.class, OutEvent.ActivityStarted.class, OutEvent.ActivityFinished.class, OutEvent.AnswerDelta.class, OutEvent.TurnEnded.class);
        assertThat(client.requests()).hasSize(2);
        assertThat(client.requests().get(1).messages())
                .filteredOn(message -> message.role() == Role.TOOL)
                .singleElement()
                .satisfies(message -> assertThat(message.content()).isEqualTo("echo: hi"));
    }

    @Test
    @DisplayName("an unknown tool never crashes the turn — the model reads the error and can correct itself")
    void should_self_repair_on_an_unknown_tool() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.toolCallStep("c1", "no-such-tool", "{}"), ScriptedClient.textStep("corrected")));
        final AgentLoop loop = loop(client, List.of());

        // When
        loop.runTurn("go", new CancelToken());

        // Then
        assertThat(out.kinds()).contains(OutEvent.ActivityFailed.class);
        assertThat(client.requests().get(1).messages().getLast().content()).contains("Unknown tool");
        assertThat(out.answerText()).isEqualTo("corrected");
    }

    @Test
    @DisplayName("a denied call feeds the denial back as the tool result")
    void should_feed_a_denial_back_to_the_model() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.toolCallStep("c1", "edit", "{}"), ScriptedClient.textStep("understood")));
        final StubTool edit = new StubTool("edit", ToolKind.EDIT, PermissionDecision.ASK, params -> new ToolResult.Success("edited", new Display.Text("edited")));
        final ToolGate denying = (tool, params) -> new GateDecision.Denied("the user denied it");
        final AgentLoop loop = new AgentLoop(conversation, registry(client), new ToolRegistry(List.of(edit)), denying, parser(), out, 10);

        // When
        loop.runTurn("edit it", new CancelToken());

        // Then
        assertThat(client.requests().get(1).messages().getLast().content()).contains("Permission denied").contains("the user denied it");
    }

    @Test
    @DisplayName("the step limit stops a runaway turn and says so")
    void should_stop_at_the_step_limit_and_say_so() {
        // Given: the model keeps calling a tool with fresh arguments, two steps allowed
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.toolCallStep("c1", "echo", "{\"text\": \"1\"}"), ScriptedClient.toolCallStep("c2", "echo", "{\"text\": \"2\"}")));
        final StubTool echo = new StubTool("echo", ToolKind.READ, PermissionDecision.ALLOW, params -> new ToolResult.Success("ok", new Display.Text("ok")));
        final AgentLoop loop = new AgentLoop(conversation, registry(client), new ToolRegistry(List.of(echo)), ALLOW_ALL_GATE, parser(), out, 2);

        // When
        loop.runTurn("go", new CancelToken());

        // Then
        assertThat(out.answerText()).contains("Step limit reached");
        assertThat(out.kinds().getLast()).isEqualTo(OutEvent.TurnEnded.class);
    }

    @Test
    @DisplayName("a turn stuck repeating the same call is broken by the loop detector")
    void should_break_a_repeating_turn() {
        // Given: the same call three times
        final List<LlmStreamEvent> sameCall = ScriptedClient.toolCallStep("c", "echo", "{\"text\": \"same\"}");
        final ScriptedClient client = new ScriptedClient(List.of(sameCall, sameCall, sameCall));
        final StubTool echo = new StubTool("echo", ToolKind.READ, PermissionDecision.ALLOW, params -> new ToolResult.Success("ok", new Display.Text("ok")));
        final AgentLoop loop = loop(client, List.of(echo));

        // When
        loop.runTurn("go", new CancelToken());

        // Then
        assertThat(out.answerText()).contains("repeating");
        assertThat(out.kinds().getLast()).isEqualTo(OutEvent.TurnEnded.class);
    }

    @Test
    @DisplayName("a model failure ends the turn with a visible failure, and the conversation stays usable")
    void should_report_a_failed_turn() {
        // Given: the script is exhausted immediately
        final AgentLoop loop = loop(new ScriptedClient(List.of()), List.of());

        // When
        loop.runTurn("hi", new CancelToken());

        // Then
        assertThat(out.kinds().getLast()).isEqualTo(OutEvent.TurnFailed.class);
    }

    @Test
    @DisplayName("thinking deltas are forwarded as their own event kind")
    void should_forward_thinking_as_its_own_kind() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(List.of(new LlmStreamEvent.ThinkingDelta("hmm"), new LlmStreamEvent.TextDelta("answer"), new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.STOP))));
        final AgentLoop loop = loop(client, List.of());

        // When
        loop.runTurn("hi", new CancelToken());

        // Then
        assertThat(out.kinds()).containsExactly(OutEvent.TurnStarted.class, OutEvent.ThinkingDelta.class, OutEvent.AnswerDelta.class, OutEvent.TurnEnded.class);
    }

    @Test
    @DisplayName("a pre-cancelled turn ends without calling the model")
    void should_end_a_cancelled_turn_without_a_model_call() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of());
        final AgentLoop loop = loop(client, List.of());
        final CancelToken cancel = new CancelToken();
        cancel.cancel();

        // When
        loop.runTurn("hi", cancel);

        // Then
        assertThat(client.requests()).isEmpty();
        assertThat(out.kinds()).containsExactly(OutEvent.TurnStarted.class, OutEvent.TurnEnded.class);
    }

    @Test
    @DisplayName("tool calls execute in wire-index order even when fragments arrive out of order")
    void should_order_tool_calls_by_index() {
        // Given: index 1 streamed before index 0
        final List<LlmStreamEvent> outOfOrder = List.of(
                new LlmStreamEvent.ToolCallDelta(1, "c-second", "echo", "{\"text\": \"second\"}"),
                new LlmStreamEvent.ToolCallDelta(0, "c-first", "echo", "{\"text\": \"first\"}"),
                new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.TOOL_CALLS));
        final ScriptedClient client = new ScriptedClient(List.of(outOfOrder, ScriptedClient.textStep("done")));
        final StubTool echo = new StubTool("echo", ToolKind.READ, PermissionDecision.ALLOW, params -> new ToolResult.Success("ok " + params.string("text").orElse(""), new Display.Text("ok")));
        final AgentLoop loop = loop(client, List.of(echo));

        // When
        loop.runTurn("go", new CancelToken());

        // Then: results returned to the model in index order
        final List<String> toolResults = client.requests().get(1).messages()
                .stream()
                .filter(message -> message.role() == Role.TOOL)
                .map(message -> message.toolCallId())
                .toList();
        assertThat(toolResults).containsExactly("c-first", "c-second");
    }

    @Test
    @DisplayName("a message-less exception still produces a meaningful turn failure")
    void should_describe_a_message_less_failure() {
        // Given: a client that throws with no message
        final com.dmipi.coder.core.domain.llm.LlmClient throwing = (request, cancel, events) -> {
            throw new IllegalStateException();
        };
        final ProtocolProvider provider = new ProtocolProvider() {

            @Override
            public String protocol() {
                return "scripted";
            }

            @Override
            public com.dmipi.coder.core.domain.llm.LlmClient connect(final ModelDeclaration declaration) {
                return throwing;
            }
        };
        final ModelRegistry registry = new ModelRegistry(List.of(new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000)), List.of(provider));
        final AgentLoop loop = new AgentLoop(conversation, registry, new ToolRegistry(List.of()), ALLOW_ALL_GATE, parser(), out, 10);

        // When
        loop.runTurn("hi", new CancelToken());

        // Then
        assertThat(out.events().getLast())
                .isInstanceOfSatisfying(OutEvent.TurnFailed.class, failed -> assertThat(failed.error()).contains("IllegalStateException"));
    }

    private AgentLoop loop(final ScriptedClient client, final List<com.dmipi.coder.core.domain.tool.Tool> tools) {
        return new AgentLoop(conversation, registry(client), new ToolRegistry(tools), ALLOW_ALL_GATE, parser(), out, 10);
    }

    private static ModelRegistry registry(final ScriptedClient client) {
        final ProtocolProvider provider = new ProtocolProvider() {

            @Override
            public String protocol() {
                return "scripted";
            }

            @Override
            public com.dmipi.coder.core.domain.llm.LlmClient connect(final ModelDeclaration declaration) {
                return client;
            }
        };
        return new ModelRegistry(List.of(new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000)), List.of(provider));
    }

    private static JacksonToolParamsParser parser() {
        return new JacksonToolParamsParser(JsonMapper.builder().build());
    }
}
