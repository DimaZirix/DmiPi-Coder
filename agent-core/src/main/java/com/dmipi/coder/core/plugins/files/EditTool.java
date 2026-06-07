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

/**
 * Replaces an exact string in a file. The old string must match uniquely unless replace_all is
 * set — an ambiguous match fails with a count instead of guessing.
 */
final class EditTool implements Tool {

    private static final int PREVIEW_CAP = 1_500;
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
        return "Replaces an exact string in a project file. 'old_string' must match the file content exactly (including whitespace) and uniquely, unless 'replace_all' is set.";
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

    /** The real unified diff of what would change; falls back to a bare -/+ pair when the file cannot be read. */
    @Override
    public String preview(final ToolParams params) {
        try {
            final String content = files.read(files.resolve(params.string("path").orElse("")));
            final String diff = UnifiedDiffs.between(params.string("path").orElse(""), content, replaced(content, params));
            return diff.isEmpty() ? fallbackPreview(params) : diff;
        } catch (final RuntimeException unreadable) {
            return fallbackPreview(params);
        }
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("path").orElse("");
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final String pathParam = params.string("path").orElseThrow();
        final String oldString = params.string("old_string").orElseThrow();
        final String newString = params.string("new_string").orElseThrow();

        final Path path;
        final String content;
        try {
            path = files.resolve(pathParam);
            content = files.read(path);
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure(failure.getMessage());
        }

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
        final int replaced = replaceAll ? occurrences : 1;
        return new ToolResult.Success("Replaced " + replaced + " occurrence(s) in " + pathParam + ".", new Display.Diff(UnifiedDiffs.between(pathParam, content, revised)));
    }

    private static String replaced(final String content, final ToolParams params) {
        return content.replace(params.string("old_string").orElse(""), params.string("new_string").orElse(""));
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
