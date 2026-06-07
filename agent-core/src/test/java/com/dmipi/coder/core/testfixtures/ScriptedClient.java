package com.dmipi.coder.core.testfixtures;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.ChatRequest;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.LlmException;
import com.dmipi.coder.core.domain.llm.LlmStreamEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** Streams scripted steps — one event list per model call — and records every request. */
public final class ScriptedClient implements LlmClient {

    private final Deque<List<LlmStreamEvent>> steps = new ArrayDeque<>();
    private final List<ChatRequest> requests = new ArrayList<>();

    public ScriptedClient(final List<List<LlmStreamEvent>> script) {
        steps.addAll(script);
    }

    /** A step that streams text and stops. */
    public static List<LlmStreamEvent> textStep(final String text) {
        return List.of(new LlmStreamEvent.TextDelta(text), new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.STOP));
    }

    /** A step that requests one tool call. */
    public static List<LlmStreamEvent> toolCallStep(final String id, final String toolName, final String argumentsJson) {
        return List.of(new LlmStreamEvent.ToolCallDelta(0, id, toolName, argumentsJson), new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.TOOL_CALLS));
    }

    @Override
    public void stream(final ChatRequest request, final CancelToken cancel, final Consumer<LlmStreamEvent> events) {
        requests.add(request);
        if (steps.isEmpty()) {
            throw new LlmException("The script is exhausted: unexpected model call number " + requests.size() + ".");
        }
        steps.pop().forEach(events);
    }

    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }
}
