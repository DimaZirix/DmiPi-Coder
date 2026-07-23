package com.dmipi.coder.core.plugins.shell;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.shell.ShellResult;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.plugin.Shell;
import java.time.Duration;
import java.util.Optional;

/**
 * Runs a shell command in the session sandbox. Asks by default — the command line is the
 * permission preview — and reports the exit code and captured output back to the model.
 */
final class ShellTool implements Tool {

    private static final int OUTPUT_CAP = 30_000;
    private static final String FOREGROUND_SCHEMA = """
            {
              "type": "object",
              "required": ["command"],
              "properties": {
                "command": {"type": "string", "description": "The shell command to run, in the project directory."},
                "timeout_seconds": {"type": "integer", "description": "Optional timeout; clamped to the configured maximum."}
              }
            }""";
    private static final String BACKGROUND_SCHEMA = """
            {
              "type": "object",
              "required": ["command"],
              "properties": {
                "command": {"type": "string", "description": "The shell command to run, in the project directory."},
                "timeout_seconds": {"type": "integer", "description": "Optional timeout; clamped to the configured maximum."},
                "is_background": {"type": "boolean", "description": "Start a long-running command (a server, a watcher) in the background; it is stopped at session end. Do not append a trailing & yourself."}
              }
            }""";

    private final Shell shell;
    private final boolean backgroundEnabled;

    ShellTool(final Shell shell, final boolean backgroundEnabled) {
        this.shell = shell;
        this.backgroundEnabled = backgroundEnabled;
    }

    @Override
    public String name() {
        return "run_shell_command";
    }

    @Override
    public String description() {
        return "Runs a shell command in the project directory and returns its exit code and output. Do NOT use it for tasks a dedicated tool covers: read files with read_file (not cat/head/tail/sed), edit with edit (not sed/awk), create files with write_file, find files with glob (not find/ls), and search contents with grep_search (not grep/rg). Do not chain unrelated commands with && or ;. Explain any command that modifies the system before running it. Output is capped; a command exceeding its timeout is killed and its partial output returned.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.EXECUTE;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(backgroundEnabled ? BACKGROUND_SCHEMA : FOREGROUND_SCHEMA);
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        if (params.string("command").filter(command -> !command.isBlank()).isEmpty()) {
            return Optional.of("Parameter 'command' is required.");
        }
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ASK;
    }

    @Override
    public String preview(final ToolParams params) {
        return params.string("command").orElse("");
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("command").orElse("");
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final String command = params.string("command").orElseThrow();
        if (backgroundEnabled && params.bool("is_background").orElse(false)) {
            try {
                final String handle = shell.runInBackground(command);
                return new ToolResult.Success("Started in the background (" + handle + "). It runs until the session ends.", new Display.Text("background " + handle));
            } catch (final RuntimeException failure) {
                return new ToolResult.Failure("The background command could not be started: " + failure.getMessage());
            }
        }
        final Optional<Duration> timeout = params.integer("timeout_seconds").map(Duration::ofSeconds);
        final ShellResult result;
        try {
            result = shell.run(command, timeout, cancel);
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure("The command could not be run: " + failure.getMessage());
        }
        return report(command, result);
    }

    /** Labeled fields, always present, so the model can read exit status and each stream unambiguously — partial output is kept even on timeout. */
    private static ToolResult report(final String command, final ShellResult result) {
        final String body = "Command: " + command
                + "\nExit code: " + exitLabel(result)
                + "\nStdout:\n" + capped(result.stdout())
                + "\nStderr:\n" + capped(result.stderr());
        if (result.timedOut()) {
            return new ToolResult.Failure("The command was killed for exceeding its timeout.\n" + body);
        }
        if (result.cancelled()) {
            return new ToolResult.Failure("The command was cancelled by the user and killed before finishing.\n" + body);
        }
        if (!result.succeeded()) {
            return new ToolResult.Failure(body);
        }
        return new ToolResult.Success(body, new Display.Text("exit 0"));
    }

    private static String exitLabel(final ShellResult result) {
        if (result.timedOut()) {
            return "killed (timeout)";
        }
        if (result.cancelled()) {
            return "killed (cancelled)";
        }
        return String.valueOf(result.exitCode());
    }

    private static String capped(final String text) {
        if (text.isEmpty()) {
            return "(empty)";
        }
        return text.length() > OUTPUT_CAP ? text.substring(0, OUTPUT_CAP) + "\n[…output truncated]" : text;
    }
}
