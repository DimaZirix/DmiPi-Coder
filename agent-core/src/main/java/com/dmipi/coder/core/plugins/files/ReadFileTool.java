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
    private static final long MAX_FILE_BYTES = 10_000_000;
    private static final int MAX_LINE_CHARS = 4_000;
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
    private final ReadTracker readTracker;

    ReadFileTool(final FileSystem files) {
        this(files, ReadTracker.off());
    }

    ReadFileTool(final FileSystem files, final ReadTracker readTracker) {
        this.files = files;
        this.readTracker = readTracker;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Reads a text file from the project. Returns up to " + MAX_LINES + " lines per call; use 'offset' and 'limit' to read a window of a larger file. Files over " + MAX_FILE_BYTES + " bytes are refused, and a single line longer than " + MAX_LINE_CHARS + " characters is truncated with a marker.";
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

    /** Bounds the context cost of a single line — a minified one-line bundle must not flood the window. */
    private static String cappedLine(final String line) {
        return line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + "[…line truncated]" : line;
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final Path path;
        final String content;
        try {
            path = files.resolve(params.string("path").orElseThrow());
            if (files.exists(path) && files.size(path) > MAX_FILE_BYTES) {
                return new ToolResult.Failure("The file " + params.string("path").orElseThrow() + " is " + files.size(path)
                        + " bytes — over the " + MAX_FILE_BYTES + "-byte read limit. Use grep_search to find the relevant part, or run_shell_command with head/tail/sed for a slice.");
            }
            content = files.read(path);
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage());
        }
        readTracker.markRead(path);
        if (content.isEmpty()) {
            return new ToolResult.Success("(the file " + params.string("path").orElseThrow() + " is empty)", new Display.Text("empty file"));
        }

        final List<String> lines = content.lines().toList();
        final long offsetParam = Math.max(1, params.integer("offset").orElse(1L));
        if (offsetParam > Math.max(1, lines.size())) {
            return new ToolResult.Failure("The file has only " + lines.size() + " lines; offset " + offsetParam + " is past the end.");
        }
        final int offset = (int) offsetParam;
        final int limit = (int) Math.min(MAX_LINES, Math.max(1, params.integer("limit").orElse((long) MAX_LINES)));

        final int end = Math.min(lines.size(), offset - 1 + limit);
        final String banner = "[Showing lines " + offset + "-" + end + " of " + lines.size() + " total lines. Use 'offset' and 'limit' to read more.]\n";
        final StringBuilder numbered = new StringBuilder(banner);
        for (int i = offset - 1; i < end; i++) {
            numbered.append(String.format("%6d\t%s", i + 1, cappedLine(lines.get(i))));
            if (i < end - 1) {
                numbered.append('\n');
            }
        }
        return new ToolResult.Success(numbered.toString(), new Display.Text("read " + (end - offset + 1) + " lines"));
    }
}
