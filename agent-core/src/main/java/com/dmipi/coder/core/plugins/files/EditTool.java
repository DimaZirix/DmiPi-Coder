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
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces an exact string in a file. The old string must match uniquely unless replace_all is
 * set — an ambiguous match fails with a count instead of guessing.
 */
final class EditTool implements Tool {

    private static final int PREVIEW_CAP = 1_500;
    private static final int CONTEXT_LINES = 3;
    private static final int LINE_NUMBER_FIELD_WIDTH = 6;
    private static final Pattern LINE_NUMBER_PREFIX = Pattern.compile("^ *\\d+\\t");
    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["path", "old_string", "new_string"],
              "properties": {
                "path": {"type": "string", "description": "The file to edit, relative to the project directory."},
                "old_string": {"type": "string", "description": "The exact text to replace, including indentation. Must match the file uniquely unless replace_all is true."},
                "new_string": {"type": "string", "description": "The replacement text; may be empty to delete."},
                "replace_all": {"type": "boolean", "description": "Replace every occurrence instead of requiring a unique match."}
              }
            }""";

    private final FileSystem files;

    EditTool(final FileSystem files) {
        this.files = files;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Replaces an exact string in a project file. Read the file first and copy 'old_string' from it verbatim, including whitespace and indentation, with at least 3 lines of context before and after the change so the match is unique — an ambiguous or not-found match fails the edit rather than guessing. Do NOT add escape characters that are not in the file. Line-number prefixes from read_file are ignored. Set 'replace_all' to change every occurrence.";
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
        if (params.string("old_string").filter(old -> !old.isEmpty()).isEmpty()) {
            return Optional.of("Parameter 'old_string' is required and must not be empty.");
        }
        if (params.string("new_string").isEmpty()) {
            return Optional.of("Parameter 'new_string' is required (it may be an empty string).");
        }
        if (params.string("old_string").equals(params.string("new_string"))) {
            return Optional.of("'old_string' and 'new_string' are identical — nothing would change.");
        }
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ASK;
    }

    /**
     * The real unified diff of what would change. When execution would refuse — no match, or an
     * ambiguous match without replace_all — the preview says so instead of showing a diff that
     * could never be applied. Falls back to a bare -/+ pair when the file cannot be read.
     */
    @Override
    public String preview(final ToolParams params) {
        final String content;
        try {
            content = files.read(files.resolve(params.string("path").orElse("")));
        } catch (final RuntimeException unreadable) {
            return fallbackPreview(params);
        }

        final String oldString = adaptedToFile(content, params.string("old_string").orElse(""));
        final String newString = adaptedToFile(content, params.string("new_string").orElse(""));
        final int occurrences = count(content, oldString);
        if (occurrences == 0) {
            return "(old_string was not found — this edit will fail)\n" + fallbackPreview(params);
        }
        if (occurrences > 1 && !params.bool("replace_all").orElse(false)) {
            return "(old_string appears " + occurrences + " times — this edit will fail without replace_all)\n" + fallbackPreview(params);
        }
        return UnifiedDiffs.between(params.string("path").orElse(""), content, content.replace(oldString, newString));
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("path").orElse("");
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final String pathParam = params.string("path").orElseThrow();

        final Path path;
        final String content;
        try {
            path = files.resolve(pathParam);
            content = files.read(path);
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage());
        }

        final String oldString = adaptedToFile(content, stripLineNumbers(params.string("old_string").orElseThrow()));
        final String newString = adaptedToFile(content, stripLineNumbers(params.string("new_string").orElseThrow()));
        final int occurrences = count(content, oldString);
        if (occurrences == 0) {
            return new ToolResult.Failure("'old_string' was not found in " + pathParam + ". Read the file and copy the text exactly, including whitespace.");
        }
        final boolean replaceAll = params.bool("replace_all").orElse(false);
        if (occurrences > 1 && !replaceAll) {
            return new ToolResult.Failure("'old_string' appears " + occurrences + " times in " + pathParam + ". Add surrounding context to make it unique, or set replace_all.");
        }

        final String revised = content.replace(oldString, newString);
        try {
            files.write(path, revised);
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage());
        }
        return new ToolResult.Success(editedSnippet(pathParam, content, revised, oldString, newString), new Display.Diff(UnifiedDiffs.between(pathParam, content, revised)));
    }

    /** Echoes the edited region, numbered, so the model can confirm the change landed without a second read. */
    private static String editedSnippet(final String path, final String content, final String revised, final String oldString, final String newString) {
        final int at = content.indexOf(oldString);
        final int startLine = newlines(content.substring(0, at)) + 1;
        final int endLine = startLine + newlines(newString);
        final List<String> lines = revised.lines().toList();
        final int from = Math.max(1, startLine - CONTEXT_LINES);
        final int to = Math.min(lines.size(), endLine + CONTEXT_LINES);
        final StringBuilder snippet = new StringBuilder("The file " + path + " has been updated. Showing lines " + from + "-" + to + " of " + lines.size() + " from the edited file:\n");
        for (int i = from; i <= to; i++) {
            snippet.append(String.format("%6d\t%s", i, lines.get(i - 1)));
            if (i < to) {
                snippet.append('\n');
            }
        }
        return snippet.toString();
    }

    private static int newlines(final String text) {
        return (int) text.chars().filter(c -> c == '\n').count();
    }

    /**
     * read_file numbers lines cat-style ({@code %6d\t<line>}); a model may paste them into
     * old_string. Strip that exact prefix — a right-justified number field at least 6 wide,
     * then a tab — so the edit still matches. A genuine short {@code digits+tab} line (field
     * under 6 wide) is left alone.
     */
    private static String stripLineNumbers(final String text) {
        final String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            final Matcher prefix = LINE_NUMBER_PREFIX.matcher(lines[i]);
            if (prefix.find() && prefix.end() - 1 >= LINE_NUMBER_FIELD_WIDTH) {
                lines[i] = lines[i].substring(prefix.end());
            }
        }
        return String.join("\n", lines);
    }

    /**
     * The model composes strings from read_file output, which joins lines with \n — so against a
     * CRLF file its old_string would never match. Adapt the search and replacement text to the
     * file's own line separator instead of failing the whole read-edit loop.
     */
    private static String adaptedToFile(final String content, final String text) {
        if (!content.contains("\r\n")) {
            return text;
        }
        return text.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    private static String fallbackPreview(final ToolParams params) {
        return "- " + capped(params.string("old_string").orElse("")) + "\n+ " + capped(params.string("new_string").orElse(""));
    }

    private static int count(final String content, final String needle) {
        int occurrences = 0;
        for (int from = content.indexOf(needle); from >= 0; from = content.indexOf(needle, from + needle.length())) {
            occurrences++;
        }
        return occurrences;
    }

    private static String capped(final String text) {
        return text.length() > PREVIEW_CAP ? text.substring(0, PREVIEW_CAP) + "[…truncated]" : text;
    }
}
