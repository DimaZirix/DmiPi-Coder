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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Lists a directory's entries; directories carry a trailing slash. */
final class ListDirectoryTool implements Tool {

    private static final int MAX_ENTRIES = 500;
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string", "description": "The directory to list, relative to the project directory; omit for the project root."}
              }
            }""";

    private final FileSystem files;

    ListDirectoryTool(final FileSystem files) {
        this.files = files;
    }

    @Override
    public String name() {
        return "list_directory";
    }

    @Override
    public String description() {
        return "Lists the entries of a project directory, sorted; directories end with '/'.";
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
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ALLOW;
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("path").orElse(".");
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final List<String> entries;
        try {
            entries = files.list(files.resolve(params.string("path").orElse(".")));
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage());
        }

        final String path = params.string("path").orElse(".");
        if (entries.isEmpty()) {
            return new ToolResult.Success("Directory listing for " + path + ":\n(empty)", new Display.Text("empty directory"));
        }
        // Directories first (marked [DIR]), then files, each already sorted by the file system.
        final List<String> ordered = new ArrayList<>();
        entries.stream().filter(entry -> entry.endsWith("/")).forEach(entry -> ordered.add("[DIR] " + entry.substring(0, entry.length() - 1)));
        entries.stream().filter(entry -> !entry.endsWith("/")).forEach(ordered::add);
        final List<String> shown = ordered.size() > MAX_ENTRIES ? ordered.subList(0, MAX_ENTRIES) : ordered;
        final String header = "Directory listing for " + path + ":\n";
        final String footer = ordered.size() > MAX_ENTRIES ? "\n[showing the first " + MAX_ENTRIES + "; " + (ordered.size() - MAX_ENTRIES) + " more not shown]" : "";
        return new ToolResult.Success(header + String.join("\n", shown) + footer, new Display.Text("listed " + entries.size() + " entries"));
    }
}
