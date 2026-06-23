package com.dmipi.coder.core.plugins.planning;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces the agent's task list. Never asks and never mutates anything — the list reaches the
 * user as a {@link Display.Todo} payload, and the model reads back a one-line tally.
 */
final class TodoWriteTool implements Tool {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["todos"],
              "properties": {
                "todos": {
                  "type": "array",
                  "description": "The full task list; it replaces the previous list entirely.",
                  "items": {
                    "type": "object",
                    "required": ["content", "status"],
                    "properties": {
                      "content": {"type": "string", "description": "The task, as one short imperative sentence."},
                      "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]}
                    }
                  }
                }
              }
            }""";

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public String description() {
        return "Replaces the task list shown to the user. Use it for any task that takes more than a couple of steps: write the planned steps up front, keep exactly one task in_progress while you work it, and mark it completed the moment it is done (do not batch completions). Send the COMPLETE list on every call — it replaces the previous list entirely. Skip it for single-step work.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.OTHER;
    }

    /** The todo list is main-session state; a subagent never inherits this tool. */
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
        try {
            parse(params);
        } catch (final IllegalArgumentException error) {
            return Optional.of(error.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ALLOW;
    }

    @Override
    public String callSummary(final ToolParams params) {
        try {
            return tally(parse(params));
        } catch (final IllegalArgumentException error) {
            return "";
        }
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final List<Display.Todo.Item> items = parse(params);
        return new ToolResult.Success(checklist(items), new Display.Todo(items));
    }

    /** Echoes the whole list back so the model re-reads its current plan on every call, not just a count. */
    private static String checklist(final List<Display.Todo.Item> items) {
        if (items.isEmpty()) {
            return "The task list is now empty.";
        }
        final StringBuilder out = new StringBuilder("Task list updated (" + tally(items) + "):\n");
        for (final Display.Todo.Item item : items) {
            out.append(mark(item.status())).append(' ').append(item.text()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String mark(final Display.Todo.Status status) {
        return switch (status) {
            case COMPLETED -> "- [x]";
            case IN_PROGRESS -> "- [~]";
            case PENDING -> "- [ ]";
        };
    }

    private static List<Display.Todo.Item> parse(final ToolParams params) {
        final JsonNode todos = MAPPER.readTree(params.rawJson()).path("todos");
        if (!todos.isArray()) {
            throw new IllegalArgumentException("Parameter 'todos' is required and must be an array.");
        }
        final List<Display.Todo.Item> items = new ArrayList<>();
        for (final JsonNode todo : todos) {
            items.add(item(todo));
        }
        return items;
    }

    private static Display.Todo.Item item(final JsonNode todo) {
        final JsonNode content = todo.path("content");
        if (!content.isString() || content.stringValue().isBlank()) {
            throw new IllegalArgumentException("Each todo needs a non-empty 'content' string.");
        }
        return new Display.Todo.Item(content.stringValue(), status(todo.path("status")));
    }

    private static Display.Todo.Status status(final JsonNode status) {
        return switch (status.isString() ? status.stringValue() : "") {
            case "pending" -> Display.Todo.Status.PENDING;
            case "in_progress" -> Display.Todo.Status.IN_PROGRESS;
            case "completed" -> Display.Todo.Status.COMPLETED;
            default -> throw new IllegalArgumentException("Each todo needs a 'status' of pending, in_progress or completed.");
        };
    }

    private static String tally(final List<Display.Todo.Item> items) {
        final long completed = count(items, Display.Todo.Status.COMPLETED);
        final long inProgress = count(items, Display.Todo.Status.IN_PROGRESS);
        final long pending = count(items, Display.Todo.Status.PENDING);
        return items.size() + " task" + (items.size() == 1 ? "" : "s")
                + " (" + completed + " completed, " + inProgress + " in progress, " + pending + " pending)";
    }

    private static long count(final List<Display.Todo.Item> items, final Display.Todo.Status status) {
        return items.stream()
                .filter(item -> item.status() == status)
                .count();
    }
}
