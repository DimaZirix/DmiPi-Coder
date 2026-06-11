package com.dmipi.coder.core.api;

import com.dmipi.coder.core.domain.agent.AgentLoop;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.agent.Conversation;
import com.dmipi.coder.core.domain.event.Out;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelRegistry;
import com.dmipi.coder.core.domain.llm.Role;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolGate;
import com.dmipi.coder.core.domain.tool.ToolParamsParser;
import com.dmipi.coder.core.domain.tool.ToolRegistry;
import com.dmipi.coder.core.plugin.Conversations;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The Conversations capability: runs a nested loop over the same gate, on a tier-resolved fixed
 * client, streaming to the separate subagent output. Each plugin receives a view bound to its
 * own identity, so the inheritance rule — the <em>other</em> plugins' tools, never main-only
 * tools — is computed by the core, not declared by the caller.
 */
final class ConversationsEngine {

    private volatile ModelRegistry models;
    private volatile ToolGate gate;
    private volatile ToolParamsParser paramsParser;
    private volatile Out subagentOut;
    private volatile List<List<Tool>> toolsByPlugin;

    void bind(final ModelRegistry models, final ToolGate gate, final ToolParamsParser paramsParser, final Out subagentOut, final List<List<Tool>> toolsByPlugin) {
        this.models = models;
        this.gate = gate;
        this.paramsParser = paramsParser;
        this.subagentOut = subagentOut;
        this.toolsByPlugin = List.copyOf(toolsByPlugin);
    }

    /** The view handed to the plugin installed at {@code pluginIndex}; its own tools are excluded from what subagents inherit. */
    Conversations forPlugin(final int pluginIndex) {
        return (request, cancel) -> run(pluginIndex, request, cancel);
    }

    private String run(final int pluginIndex, final Conversations.SubagentRequest request, final CancelToken cancel) {
        if (models == null) {
            throw new IllegalStateException("The Conversations capability is not usable during install — hold it and call it later.");
        }

        final LlmClient client = request.preferredTier()
                .map(tier -> models.atLeast(tier))
                .orElseGet(models::active)
                .client();
        final Conversation conversation = new Conversation(request.instructions());
        final FailureWatch watch = new FailureWatch(subagentOut);
        new AgentLoop(conversation, () -> client, new ToolRegistry(inherited(pluginIndex)), gate, paramsParser, watch, request.maxSteps())
                .runTurn(request.task(), cancel);
        if (watch.failure != null) {
            throw new IllegalStateException("The subagent failed: " + watch.failure);
        }
        return summaryOf(conversation);
    }

    private List<Tool> inherited(final int pluginIndex) {
        final Set<Tool> own = Collections.newSetFromMap(new IdentityHashMap<>());
        own.addAll(toolsByPlugin.get(pluginIndex));
        return toolsByPlugin.stream()
                .flatMap(List::stream)
                .filter(tool -> !own.contains(tool))
                .filter(tool -> !tool.mainOnly())
                .toList();
    }

    /** The subagent's last plain answer — what the parent reads as the result of the delegation. */
    private static String summaryOf(final Conversation conversation) {
        final List<ChatMessage> messages = conversation.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            final ChatMessage message = messages.get(i);
            if (message.role() == Role.ASSISTANT && !message.content().isBlank()) {
                return message.content();
            }
        }
        return "(the subagent produced no summary)";
    }

    /** Forwards every event to the subagent output while remembering a turn failure. */
    private static final class FailureWatch implements Out {

        private final Out delegate;
        private volatile String failure;

        private FailureWatch(final Out delegate) {
            this.delegate = delegate;
        }

        @Override
        public void event(final OutEvent event) {
            if (event instanceof OutEvent.TurnFailed(final String error)) {
                failure = error;
            }
            delegate.event(event);
        }
    }
}
