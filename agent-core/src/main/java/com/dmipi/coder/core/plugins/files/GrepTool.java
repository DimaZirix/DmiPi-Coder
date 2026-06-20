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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Searches file contents for a regular expression, reporting matching lines with their locations. */
final class GrepTool implements Tool {

    private static final int MAX_MATCHES = 200;
    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final String DEFAULT_GLOB = "**/*";
    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["pattern"],
              "properties": {
                "pattern": {"type": "string", "description": "A Java regular expression to search for, tested against each line."},
                "glob": {"type": "string", "description": "Limit the search to files matching this glob (default all files)."}
              }
            }""";

    private final FileSystem files;

    GrepTool(final FileSystem files) {
        this.files = files;
    }

    @Override
    public String name() {
        return "grep_search";
    }

    @Override
    public String description() {
        return "Searches project file contents for a regular expression and returns matching lines as path:line: text. Narrow the search with 'glob'. Build/VCS directories, binary files and files over 1 MB are skipped.";
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
        final Optional<String> pattern = params.string("pattern").filter(value -> !value.isBlank());
        if (pattern.isEmpty()) {
            return Optional.of("Parameter 'pattern' is required.");
        }
        try {
            Pattern.compile(pattern.orElseThrow());
        } catch (final PatternSyntaxException invalid) {
            return Optional.of("Parameter 'pattern' is not a valid regular expression: " + invalid.getMessage());
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
        final Pattern pattern = Pattern.compile(params.string("pattern").orElseThrow());
        final List<Path> candidates;
        try {
            candidates = files.find(params.string("glob").filter(glob -> !glob.isBlank()).orElse(DEFAULT_GLOB));
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage());
        }

        final List<String> hits = new ArrayList<>();
        for (final Path file : candidates) {
            if (cancel.isCancelled() || hits.size() >= MAX_MATCHES) {
                break;
            }
            collectMatches(file, pattern, hits);
        }

        final String patternText = params.string("pattern").orElseThrow();
        if (hits.isEmpty()) {
            return new ToolResult.Success("No matches for \"" + patternText + "\".", new Display.Text("no matches"));
        }
        final boolean capped = hits.size() >= MAX_MATCHES;
        final String header = "Found " + hits.size() + (capped ? "+" : "") + " matching line(s) for \"" + patternText + "\":\n";
        final String footer = capped ? "\n[stopped at " + MAX_MATCHES + " matches; narrow the pattern or glob]" : "";
        return new ToolResult.Success(header + String.join("\n", hits) + footer, new Display.Text(hits.size() + (capped ? "+" : "") + " match(es)"));
    }

    private void collectMatches(final Path file, final Pattern pattern, final List<String> hits) {
        final String content;
        try {
            if (files.size(file) > MAX_FILE_BYTES) {
                return; // oversized file — matching it would flood memory for marginal value
            }
            content = files.read(file);
        } catch (final RuntimeException unreadable) {
            return; // binary or unreadable file — skip it, do not fail the whole search
        }
        final List<String> lines = content.lines().toList();
        for (int line = 0; line < lines.size() && hits.size() < MAX_MATCHES; line++) {
            if (pattern.matcher(lines.get(line)).find()) {
                hits.add(file + ":" + (line + 1) + ": " + lines.get(line).strip());
            }
        }
    }
}
