package com.dmipi.coder.core.domain.agent;

import com.dmipi.coder.core.domain.event.Out;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.ChatRequest;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.LlmStreamEvent;
import com.dmipi.coder.core.domain.llm.ModelRegistry;
import com.dmipi.coder.core.domain.llm.ToolCall;
import com.dmipi.coder.core.domain.permissions.GateDecision;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolGate;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolParamsParser;
import com.dmipi.coder.core.domain.tool.ToolRegistry;
import com.dmipi.coder.core.domain.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The turn loop: a turn runs as steps — the model speaks and requests tool calls; each call is
 * validated, gated and executed; results return to the history; the model continues — until it
 * answers, a limit hits, it repeats itself, it is cancelled, or it fails. Every ending is
 * explicit. A bad call never crashes the turn: the model gets the error back and can correct
 * itself.
 */
public final class AgentLoop {

    private static final String STEP_LIMIT_NOTE = "\n[Step limit reached after %d steps. Send another prompt to continue.]";
    private static final String REPETITION_NOTE = "\n[The turn was stopped: the model kept repeating the same tool call.]";

    private final Conversation conversation;
    private final Supplier<LlmClient> client;
    private final ToolRegistry tools;
    private final ToolGate gate;
    private final ToolParamsParser paramsParser;
    private final Out out;
    private final int maxStepsPerTurn;
    private final LoopGuards guards;

    /** The main loop follows the registry's active model, so a runtime switch applies mid-conversation. */
    public AgentLoop(final Conversation conversation, final ModelRegistry models, final ToolRegistry tools, final ToolGate gate, final ToolParamsParser paramsParser, final Out out, final int maxStepsPerTurn) {
        this(conversation, () -> models.active().client(), tools, gate, paramsParser, out, maxStepsPerTurn, LoopGuards.none());
    }

    /** A nested (subagent) loop runs on one fixed client for its whole life; its context is throwaway — no guards. */
    public AgentLoop(final Conversation conversation, final Supplier<LlmClient> client, final ToolRegistry tools, final ToolGate gate, final ToolParamsParser paramsParser, final Out out, final int maxStepsPerTurn) {
        this(conversation, client, tools, gate, paramsParser, out, maxStepsPerTurn, LoopGuards.none());
    }

    /** The fully wired main loop: compaction, the next-speaker nudge, and transient reminders. */
    public AgentLoop(final Conversation conversation, final ModelRegistry models, final ToolRegistry tools, final ToolGate gate, final ToolParamsParser paramsParser, final Out out, final int maxStepsPerTurn, final LoopGuards guards) {
        this(conversation, () -> models.active().client(), tools, gate, paramsParser, out, maxStepsPerTurn, guards);
    }

    private AgentLoop(final Conversation conversation, final Supplier<LlmClient> client, final ToolRegistry tools, final ToolGate gate, final ToolParamsParser paramsParser, final Out out, final int maxStepsPerTurn, final LoopGuards guards) {
        this.conversation = conversation;
        this.client = client;
        this.tools = tools;
        this.gate = gate;
        this.paramsParser = paramsParser;
        this.out = out;
        this.maxStepsPerTurn = maxStepsPerTurn;
        this.guards = guards;
    }

    /** Runs one full turn for the user input; the conversation stays usable whatever the ending. */
    public void runTurn(final String userInput, final CancelToken cancel) {
        out.event(new OutEvent.TurnStarted());
        conversation.add(ChatMessage.user(userInput));
        try {
            runSteps(cancel);
            out.event(new OutEvent.TurnEnded());
        } catch (final RuntimeException failure) {
            if (cancel.isCancelled()) {
                out.event(new OutEvent.TurnEnded());
                return;
            }
            out.event(new OutEvent.TurnFailed(failure.getMessage() != null ? failure.getMessage() : failure.toString()));
        }
    }

    private void runSteps(final CancelToken cancel) {
        final LoopDetector loopDetector = new LoopDetector();
        boolean nudged = false;
        for (int step = 1; step <= maxStepsPerTurn; step++) {
            if (cancel.isCancelled()) {
                return;
            }
            guards.wiredContextManager().ifPresent(manager -> manager.maybeCompact(conversation, cancel));

            final Step result = streamStep(cancel, step);
            conversation.add(ChatMessage.assistant(result.text(), result.toolCalls()));
            if (result.toolCalls().isEmpty()) {
                // One nudge per turn: a model that stalls again after being told to continue gets the turn back to the user.
                final boolean announcedMoreWork = !nudged && !cancel.isCancelled() && guards.wiredNextSpeaker()
                        .map(check -> check.modelShouldContinue(result.text(), cancel))
                        .orElse(false);
                if (!announcedMoreWork) {
                    return;
                }
                nudged = true;
                conversation.add(ChatMessage.user("[Your last message announced more work. Continue and finish it now.]"));
                continue;
            }
            if (loopDetector.repetitionDetected(result.toolCalls())) {
                out.event(new OutEvent.AnswerDelta(REPETITION_NOTE));
                return;
            }

            for (final ToolCall call : result.toolCalls()) {
                conversation.add(ChatMessage.toolResult(call.id(), executeCall(call, cancel)));
            }
        }
        out.event(new OutEvent.AnswerDelta(STEP_LIMIT_NOTE.formatted(maxStepsPerTurn)));
    }

    private Step streamStep(final CancelToken cancel, final int step) {
        final StringBuilder text = new StringBuilder();
        // Keyed and iterated by wire index: fragments may arrive out of order, execution may not.
        final Map<Integer, PendingCall> pending = new TreeMap<>();
        final List<ChatMessage> requestMessages = guards.wiredReminders()
                .map(reminders -> reminders.applyTo(conversation.messages(), step))
                .orElseGet(conversation::messages);
        client.get().stream(new ChatRequest(requestMessages, tools.schemas()), cancel, event -> {
            switch (event) {
                case LlmStreamEvent.TextDelta(final String delta) -> {
                    text.append(delta);
                    out.event(new OutEvent.AnswerDelta(delta));
                }
                case LlmStreamEvent.ThinkingDelta(final String delta) -> out.event(new OutEvent.ThinkingDelta(delta));
                case LlmStreamEvent.ToolCallDelta(final int index, final String id, final String name, final String argumentsDelta) -> pending.computeIfAbsent(index, unused -> new PendingCall()).absorb(id, name, argumentsDelta);
                case LlmStreamEvent.Finished ignored -> {
                }
            }
        });

        final List<ToolCall> calls = new ArrayList<>();
        pending.forEach((index, call) -> calls.add(call.toToolCall(index)));
        return new Step(text.toString(), List.copyOf(calls));
    }

    /** Executes one call end to end and returns what the model reads as the result. */
    private String executeCall(final ToolCall call, final CancelToken cancel) {
        final Optional<Tool> tool = tools.named(call.name());
        if (tool.isEmpty()) {
            return failed(call.name(), "Unknown tool '" + call.name() + "'.");
        }

        final ToolParams params;
        try {
            params = paramsParser.parse(call.argumentsJson());
        } catch (final IllegalArgumentException invalid) {
            return failed(call.name(), invalid.getMessage());
        }

        final Optional<String> invalid = tool.orElseThrow().validate(params);
        if (invalid.isPresent()) {
            return failed(call.name(), invalid.orElseThrow());
        }

        if (gate.decide(tool.orElseThrow(), params) instanceof GateDecision.Denied(final String reason)) {
            return failed(call.name(), "Permission denied: " + reason);
        }

        out.event(new OutEvent.ActivityStarted(call.name(), tool.orElseThrow().callSummary(params)));
        final ToolResult result = execute(tool.orElseThrow(), params, cancel);
        switch (result) {
            case ToolResult.Success(final String ignored, final var display) -> out.event(new OutEvent.ActivityFinished(call.name(), display));
            case ToolResult.Failure(final String error) -> out.event(new OutEvent.ActivityFailed(call.name(), error));
        }
        return result.llmContent();
    }

    private static ToolResult execute(final Tool tool, final ToolParams params, final CancelToken cancel) {
        try {
            return tool.execute(params, cancel);
        } catch (final RuntimeException crashed) {
            return new ToolResult.Failure("The tool failed unexpectedly: " + crashed.getMessage());
        }
    }

    private String failed(final String toolName, final String error) {
        out.event(new OutEvent.ActivityFailed(toolName, error));
        return error;
    }

    private record Step(String text, List<ToolCall> toolCalls) {
    }

    /** Assembles one tool call from its stream fragments. */
    private static final class PendingCall {

        private final StringBuilder arguments = new StringBuilder();
        private String id = "";
        private String name = "";

        void absorb(final String idPart, final String namePart, final String argumentsPart) {
            if (!idPart.isEmpty()) {
                id = idPart;
            }
            if (!namePart.isEmpty()) {
                name = namePart;
            }
            arguments.append(argumentsPart);
        }

        ToolCall toToolCall(final int index) {
            final String callId = id.isEmpty() ? "call_" + index : id;
            return new ToolCall(callId, name, arguments.toString());
        }
    }
}
