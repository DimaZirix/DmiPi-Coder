package com.dmipi.coder.core.plugins.files;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.infrastructure.files.UnifiedDiffs;
import com.dmipi.coder.core.plugin.FileSystem;
import java.nio.file.Path;
import java.util.Optional;

/** Writes a whole file — creating it or replacing its content; the permission preview shows what would be written. */
final class WriteFileTool implements Tool {

    private static final int PREVIEW_CAP = 2_000;
    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["path", "content"],
              "properties": {
                "path": {"type": "string", "description": "The file to write, relative to the project directory."},
                "content": {"type": "string", "description": "The complete new content of the file."}
              }
            }""";

    private final FileSystem files;

    WriteFileTool(final FileSystem files) {
        this.files = files;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Creates a file or replaces its whole content. For a change inside an existing file, prefer 'edit'.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.EDIT;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(SCHEMA);
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        if (params.string("path").filter(path -> !path.isBlank()).isEmpty()) {
            return Optional.of("Parameter 'path' is required.");
        }
        if (params.string("content").isEmpty()) {
            return Optional.of("Parameter 'content' is required.");
        }
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ASK;
    }

    /** The unified diff against the current content (all additions for a new file); capped content as fallback. */
    @Override
    public String preview(final ToolParams params) {
        final String content = params.string("content").orElse("");
        try {
            final Path path = files.resolve(params.string("path").orElse(""));
            final String before = files.exists(path) ? files.read(path) : "";
            final String diff = UnifiedDiffs.between(params.string("path").orElse(""), before, content);
            return diff.isEmpty() ? "(no change)" : diff;
        } catch (final RuntimeException unreadable) {
            return content.length() > PREVIEW_CAP ? content.substring(0, PREVIEW_CAP) + "\n[…truncated]" : content;
        }
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("path").orElse("");
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        try {
            final String pathParam = params.string("path").orElseThrow();
            final Path path = files.resolve(pathParam);
            final String content = params.string("content").orElseThrow();
            final boolean existed = files.exists(path);
            final String before = existed ? files.read(path) : "";
            files.write(path, content);
            final String verb = existed ? "Overwrote" : "Created and wrote to new file";
            return new ToolResult.Success(verb + " " + pathParam + " (" + content.length() + " characters).", new Display.Diff(UnifiedDiffs.between(pathParam, before, content)));
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage());
        }
    }
}
