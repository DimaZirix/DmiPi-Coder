package com.dmipi.coder.core.plugins.files;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.plugin.FileSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Reads a text file, windowed by line offset and limit so a huge file never floods the history. */
final class ReadFileTool implements Tool {

    private static final int MAX_LINES = 2_000;
    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["path"],
              "properties": {
                "path": {"type": "string", "description": "The file to read, relative to the project directory."},
                "offset": {"type": "integer", "description": "1-based line to start from; omit to start at the beginning."},
                "limit": {"type": "integer", "description": "Maximum lines to return; omit for the default window."}
              }
            }""";

    private final FileSystem files;

    ReadFileTool(final FileSystem files) {
        this.files = files;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Reads a text file from the project. Returns up to " + MAX_LINES + " lines per call; use 'offset' and 'limit' to read a window of a larger file.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.READ;
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
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ALLOW;
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("path").orElse("");
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final Path path;
        final String content;
        try {
            path = files.resolve(params.string("path").orElseThrow());
            content = files.read(path);
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage());
        }

        final List<String> lines = content.lines().toList();
        final int offset = (int) Math.max(1, params.integer("offset").orElse(1L));
        final int limit = (int) Math.min(MAX_LINES, Math.max(1, params.integer("limit").orElse((long) MAX_LINES)));
        if (offset > lines.size() && !lines.isEmpty()) {
            return new ToolResult.Failure("The file has only " + lines.size() + " lines; offset " + offset + " is past the end.");
        }

        final int end = Math.min(lines.size(), offset - 1 + limit);
        final String window = String.join("\n", lines.subList(offset - 1, end));
        final String note = end < lines.size() ? "\n[showing lines " + offset + "-" + end + " of " + lines.size() + "; continue with offset " + (end + 1) + "]" : "";
        return new ToolResult.Success(window + note, new Display.Text("read " + (end - offset + 1) + " lines"));
    }
}
