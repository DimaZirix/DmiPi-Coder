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
                "pattern": {"type": "string", "description": "A Java regular expression, tested against each line. Case-insensitive unless case_sensitive is true."},
                "glob": {"type": "string", "description": "Limit the search to files matching this glob (default all files)."},
                "path": {"type": "string", "description": "Limit the search to files under this project-relative directory."},
                "case_sensitive": {"type": "boolean", "description": "Match case-sensitively (default false)."},
                "limit": {"type": "integer", "description": "Maximum matches to return (default and maximum 200)."}
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
        return "Searches project file contents for a regular expression and returns matching lines as path:line: text. ALWAYS use this instead of running grep or rg through the shell. The pattern is case-insensitive unless case_sensitive is set; narrow the search with 'glob' or 'path'. Build/VCS directories, binary files and files over 1 MB are skipped.";
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
        final String patternText = params.string("pattern").orElseThrow();
        final int flags = params.bool("case_sensitive").orElse(false) ? 0 : Pattern.CASE_INSENSITIVE;
        final Pattern pattern = Pattern.compile(patternText, flags);
        final int limit = (int) Math.min(MAX_MATCHES, Math.max(1, params.integer("limit").orElse((long) MAX_MATCHES)));
        final List<Path> candidates;
        final Optional<Path> subtree;
        try {
            candidates = files.find(params.string("glob").filter(glob -> !glob.isBlank()).orElse(DEFAULT_GLOB));
            subtree = params.string("path").filter(value -> !value.isBlank()).map(files::resolve);
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage());
        }

        final List<String> hits = new ArrayList<>();
        int skipped = 0;
        for (final Path file : candidates) {
            if (cancel.isCancelled() || hits.size() >= limit) {
                break;
            }
            if (subtree.isPresent() && !file.startsWith(subtree.orElseThrow())) {
                continue;
            }
            if (!collectMatches(file, pattern, hits, limit)) {
                skipped++;
            }
        }

        if (hits.isEmpty()) {
            return new ToolResult.Success("No matches for \"" + patternText + "\".", new Display.Text("no matches"));
        }
        final boolean capped = hits.size() >= limit;
        final String header = "Found " + hits.size() + (capped ? "+" : "") + " matching line(s) for \"" + patternText + "\":\n";
        final StringBuilder footer = new StringBuilder();
        if (capped) {
            footer.append("\n[stopped at ").append(limit).append(" matches; narrow the pattern or raise 'limit']");
        }
        if (skipped > 0) {
            footer.append("\n[").append(skipped).append(" file(s) skipped (unreadable, binary, or over 1 MB)]");
        }
        return new ToolResult.Success(header + String.join("\n", hits) + footer, new Display.Text(hits.size() + (capped ? "+" : "") + " match(es)"));
    }

    /** Collects a file's matches; returns false when the file was skipped (oversized, binary, or unreadable). */
    private boolean collectMatches(final Path file, final Pattern pattern, final List<String> hits, final int limit) {
        final String content;
        try {
            if (files.size(file) > MAX_FILE_BYTES) {
                return false;
            }
            content = files.read(file);
        } catch (final RuntimeException unreadable) {
            return false;
        }
        final List<String> lines = content.lines().toList();
        for (int line = 0; line < lines.size() && hits.size() < limit; line++) {
            if (pattern.matcher(lines.get(line)).find()) {
                hits.add(file + ":" + (line + 1) + ": " + lines.get(line).strip());
            }
        }
        return true;
    }
}
