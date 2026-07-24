package com.dmipi.coder.core.plugins.memory;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.infrastructure.files.UnifiedDiffs;
import java.util.Optional;

/**
 * Reads and saves standing memory. A save asks with the diff as preview; a project-scope save is
 * an ordinary file edit, while a user-scope save reports {@link ToolKind#EXECUTE} — its blast
 * radius is every future session in every project, so allow-edits must not auto-approve it.
 */
final class MemoryTool implements Tool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["action", "scope"],
              "properties": {
                "action": {"type": "string", "enum": ["read", "save"], "description": "read returns the scope's memory file as saved on disk (@import lines unexpanded); save replaces it."},
                "scope": {"type": "string", "enum": ["user", "project"], "description": "project for facts about this project; user for personal preferences that apply everywhere."},
                "content": {"type": "string", "description": "On save: the complete new memory content. Read first, then save the full updated text."}
              }
            }""";

    private final MemoryStore store;

    MemoryTool(final MemoryStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "Reads or saves standing memory that carries across sessions. Save a project fact to scope 'project' and a personal preference to scope 'user'. A save replaces the scope's whole memory file: read it first, then save the complete updated content — read returns the file exactly as saved (@import lines stay unexpanded), so the round-trip never flattens them. Keep memory short — rules and pointers, not prose.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.EDIT;
    }

    @Override
    public ToolKind kind(final ToolParams params) {
        if (!isSave(params)) {
            return ToolKind.READ;
        }
        return scope(params) == MemoryScope.USER ? ToolKind.EXECUTE : ToolKind.EDIT;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(SCHEMA);
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        final Optional<String> action = params.string("action");
        if (action.isEmpty() || !(action.orElseThrow().equals("read") || action.orElseThrow().equals("save"))) {
            return Optional.of("Parameter 'action' must be 'read' or 'save'.");
        }
        try {
            MemoryScope.of(params.string("scope").orElse(""));
        } catch (final IllegalArgumentException error) {
            return Optional.of(error.getMessage());
        }
        if (isSave(params) && params.string("content").isEmpty()) {
            return Optional.of("A save requires 'content' — the complete new memory text.");
        }
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return isSave(params) ? PermissionDecision.ASK : PermissionDecision.ALLOW;
    }

    @Override
    public String preview(final ToolParams params) {
        if (!isSave(params)) {
            return "";
        }
        final MemoryScope scope = scope(params);
        return UnifiedDiffs.between(store.targetLabel(scope), store.rawContent(scope), params.string("content").orElse(""));
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("action").orElse("?") + " " + params.string("scope").orElse("?") + " memory";
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final MemoryScope scope = scope(params);
        if (!isSave(params)) {
            // The raw file, not the inlined view: a read feeds the save workflow, and saving an
            // inlined view back would bake every @import's content in and sever the link.
            final String raw = store.rawContent(scope);
            return new ToolResult.Success(
                    raw.isEmpty() ? "(no " + scope.label() + " memory saved)" : raw,
                    new Display.Text("read " + scope.label() + " memory"));
        }
        final String diff = preview(params);
        store.save(scope, params.string("content").orElseThrow());
        return new ToolResult.Success(
                "Saved. The " + scope.label() + " memory applies from the next session; this conversation already knows it.",
                new Display.Diff(diff));
    }

    private static boolean isSave(final ToolParams params) {
        return params.string("action").map("save"::equals).orElse(false);
    }

    private static MemoryScope scope(final ToolParams params) {
        return MemoryScope.of(params.string("scope").orElse(""));
    }
}
