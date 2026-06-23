package com.dmipi.coder.core.plugins.subagents;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.plugin.Conversations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Delegates a subtask to a subagent: a fresh conversation with its own context window and a
 * smaller step budget; only its summary returns. The delegation itself is free — every tool
 * call the subagent makes passes the same permission gate as a main-session call.
 */
final class TaskTool implements Tool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["type", "instruction"],
              "properties": {
                "type": {"type": "string", "description": "The subagent type to run."},
                "instruction": {"type": "string", "description": "The complete task for the subagent. It starts fresh — include everything it needs to know."}
              }
            }""";

    private final Conversations conversations;
    private final Map<String, SubagentType> types;

    TaskTool(final Conversations conversations, final List<SubagentType> types) {
        this.conversations = conversations;
        this.types = types.stream()
                .collect(Collectors.toMap(SubagentType::name, type -> type, (first, second) -> second, LinkedHashMap::new));
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "Delegates a subtask to a subagent — a fresh conversation that works on its own and returns only a summary, keeping its intermediate reads out of this context. Use it for open-ended work whose steps would flood the main context (e.g. \"find where retries are configured\"); for a quick, direct lookup you already know how to do, use read_file/grep_search yourself instead. The subagent starts blank, so state the task completely. Available types:\n" + listing();
    }

    @Override
    public ToolKind kind() {
        return ToolKind.OTHER;
    }

    /** Delegation is bound to the main session; a subagent never spawns subagents. */
    @Override
    public boolean mainOnly() {
        return true;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(SCHEMA);
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        if (params.string("type").map(types::containsKey).orElse(false)) {
            if (params.string("instruction").filter(instruction -> !instruction.isBlank()).isEmpty()) {
                return Optional.of("Parameter 'instruction' is required: the complete task for the subagent.");
            }
            return Optional.empty();
        }
        return Optional.of("Parameter 'type' must be one of: " + String.join(", ", types.keySet()) + ".");
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ALLOW;
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("type").orElse("?") + ": " + params.string("instruction").orElse("");
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final SubagentType type = types.get(params.string("type").orElseThrow());
        final Conversations.SubagentRequest request = new Conversations.SubagentRequest(
                type.instructions(),
                params.string("instruction").orElseThrow(),
                type.preferredTier(),
                type.maxSteps());
        final String summary;
        try {
            summary = conversations.run(request, cancel);
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage() != null ? failure.getMessage() : "The subagent failed.");
        }
        return new ToolResult.Success(summary, new Display.Text("subagent " + type.name() + " finished"));
    }

    private String listing() {
        return types.values()
                .stream()
                .map(type -> "- " + type.name() + ": " + type.description())
                .collect(Collectors.joining("\n"));
    }
}
