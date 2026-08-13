package com.dmipi.coder.core.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.LlmStreamEvent;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ModelRegistry;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Role;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.llm.ToolCall;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextManagerTest {

    private final RecordingOut out = new RecordingOut();

    @Test
    @DisplayName("crossing the budget compacts the older history into a summary written by the active model")
    void should_compact_when_the_budget_is_crossed() {
        // Given: a tiny window; the first turn's long answer overflows it before the second turn's step
        final ModelDeclaration tinyWindow = new ModelDeclaration("tiny", "scripted", "", Tier.FAST, 200);
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.textStep("x".repeat(1_000)),
                ScriptedClient.textStep("<state_snapshot>COMPACTED-STATE: the long answer said x.</state_snapshot>"),
                ScriptedClient.textStep("continuing")));

        // When
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(tinyWindow)
                .registerPlugin(providerPlugin(client))
                .build()) {
            coder.runTurn("talk a lot", new CancelToken());
            coder.runTurn("go on", new CancelToken());
        }

        // Then: the front-end saw the housekeeping event with a shrinking budget
        assertThat(out.events())
                .filteredOn(OutEvent.ContextCompacted.class::isInstance)
                .singleElement()
                .satisfies(event -> {
                    final OutEvent.ContextCompacted compacted = (OutEvent.ContextCompacted) event;
                    assertThat(compacted.approxTokensAfter()).isLessThan(compacted.approxTokensBefore());
                });

        // And the next model request carried the snapshot (markers stripped) instead of the long answer, under intact instructions
        assertThat(client.requests().getLast().messages().getFirst().role()).isEqualTo(Role.SYSTEM);
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("COMPACTED-STATE").doesNotContain("<state_snapshot>"))
                .noneSatisfy(message -> assertThat(message.content()).contains("x".repeat(1_000)));
    }

    @Test
    @DisplayName("tool-call arguments count toward the budget — big writes trigger compaction too")
    void should_count_tool_call_arguments_toward_the_budget() {
        // Given: a tiny window where the bulk sits in a write_file argument, not in message content
        final ModelDeclaration tinyWindow = new ModelDeclaration("tiny", "scripted", "", Tier.FAST, 200);
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.textStep("<state_snapshot>SNAP</state_snapshot>")));
        final ModelRegistry models = new ModelRegistry(List.of(tinyWindow), List.of(new ProtocolProvider() {

            @Override
            public String protocol() {
                return "scripted";
            }

            @Override
            public LlmClient connect(final ModelDeclaration declaration) {
                return client;
            }
        }));
        final ContextManager manager = new ContextManager(models, 0.5, out, "summarize");
        final Conversation conversation = new Conversation("instructions");
        conversation.add(ChatMessage.assistant("writing", List.of(new ToolCall("c1", "write_file", "{\"content\": \"" + "x".repeat(1_500) + "\"}"))));
        conversation.add(ChatMessage.toolResult("c1", "ok"));
        conversation.add(ChatMessage.user("next"));

        // When
        manager.maybeCompact(conversation, new CancelToken());

        // Then: the argument payload alone crossed the threshold and compaction ran
        assertThat(out.kinds()).contains(OutEvent.ContextCompacted.class);
    }

    @Test
    @DisplayName("a cancel during the summary stream leaves the history untouched")
    void should_not_compact_with_a_cancelled_summary() {
        // Given: an over-budget history and a model whose summary stream is cancelled midway
        final ModelDeclaration tinyWindow = new ModelDeclaration("tiny", "scripted", "", Tier.FAST, 200);
        final LlmClient cancelledMidStream = (request, cancel, events) -> {
            events.accept(new LlmStreamEvent.TextDelta("a fragment of the summ"));
            cancel.cancel();
        };
        final ModelRegistry models = new ModelRegistry(List.of(tinyWindow), List.of(new ProtocolProvider() {

            @Override
            public String protocol() {
                return "scripted";
            }

            @Override
            public LlmClient connect(final ModelDeclaration declaration) {
                return cancelledMidStream;
            }
        }));
        final ContextManager manager = new ContextManager(models, 0.5, out, "summarize");
        final Conversation conversation = new Conversation("instructions");
        conversation.add(ChatMessage.user("x".repeat(1_000)));
        conversation.add(ChatMessage.user("y".repeat(1_000)));
        final List<ChatMessage> before = List.copyOf(conversation.messages());

        // When
        manager.maybeCompact(conversation, new CancelToken());

        // Then: no fragment replaced the history, and no compaction was announced
        assertThat(conversation.messages()).isEqualTo(before);
        assertThat(out.kinds()).doesNotContain(OutEvent.ContextCompacted.class);
    }

    @Test
    @DisplayName("under the budget, nothing happens")
    void should_leave_a_small_history_alone() {
        // Given
        final ModelDeclaration roomy = new ModelDeclaration("roomy", "scripted", "", Tier.FAST, 100_000);
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.textStep("short"),
                ScriptedClient.textStep("also short")));

        // When
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(roomy)
                .registerPlugin(providerPlugin(client))
                .build()) {
            coder.runTurn("hi", new CancelToken());
            coder.runTurn("again", new CancelToken());
        }

        // Then
        assertThat(out.kinds()).doesNotContain(OutEvent.ContextCompacted.class);
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
