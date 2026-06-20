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

/** Finds files by glob pattern, so the model can locate files without listing the whole tree. */
final class GlobTool implements Tool {

    private static final int MAX_RESULTS = 500;
    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["pattern"],
              "properties": {
                "pattern": {"type": "string", "description": "A glob relative to the project directory, e.g. **/*.java or src/**/*Test.java."}
              }
            }""";

    private final FileSystem files;

    GlobTool(final FileSystem files) {
        this.files = files;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Finds project files matching a glob pattern (e.g. **/*.java). Returns matching paths, sorted. Build/VCS directories are skipped.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.SEARCH;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(SCHEMA);
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        if (params.string("pattern").filter(pattern -> !pattern.isBlank()).isEmpty()) {
            return Optional.of("Parameter 'pattern' is required.");
        }
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ALLOW;
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("pattern").orElse("");
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final List<Path> matches;
        try {
            matches = files.find(params.string("pattern").orElseThrow());
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage());
        }
        final String pattern = params.string("pattern").orElseThrow();
        if (matches.isEmpty()) {
            return new ToolResult.Success("No files match \"" + pattern + "\".", new Display.Text("no matches"));
        }

        final List<Path> shown = matches.size() > MAX_RESULTS ? matches.subList(0, MAX_RESULTS) : matches;
        final String header = "Found " + matches.size() + " file(s) matching \"" + pattern + "\":\n";
        final String listing = shown.stream()
                .map(Path::toString)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        final String footer = matches.size() > MAX_RESULTS ? "\n[showing the first " + MAX_RESULTS + "; " + (matches.size() - MAX_RESULTS) + " more not shown — refine the pattern]" : "";
        return new ToolResult.Success(header + listing + footer, new Display.Text(matches.size() + " match(es)"));
    }
}
