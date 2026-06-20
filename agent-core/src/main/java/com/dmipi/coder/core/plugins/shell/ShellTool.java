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
    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["command"],
              "properties": {
                "command": {"type": "string", "description": "The shell command to run, in the project directory."},
                "timeout_seconds": {"type": "integer", "description": "Optional timeout; clamped to the configured maximum."}
              }
            }""";

    private final Shell shell;

    ShellTool(final Shell shell) {
        this.shell = shell;
    }

    @Override
    public String name() {
        return "run_shell_command";
    }

    @Override
    public String description() {
        return "Runs a shell command in the project directory and returns its exit code and output. Output is capped; a command exceeding its timeout is killed.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.EXECUTE;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(SCHEMA);
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
                + "\nExit code: " + (result.timedOut() ? "killed (timeout)" : result.exitCode())
                + "\nStdout:\n" + capped(result.stdout())
                + "\nStderr:\n" + capped(result.stderr());
        if (result.timedOut()) {
            return new ToolResult.Failure("The command was killed for exceeding its timeout.\n" + body);
        }
        if (!result.succeeded()) {
            return new ToolResult.Failure(body);
        }
        return new ToolResult.Success(body, new Display.Text("exit 0"));
    }

    private static String capped(final String text) {
        if (text.isEmpty()) {
            return "(empty)";
        }
        return text.length() > OUTPUT_CAP ? text.substring(0, OUTPUT_CAP) + "\n[…output truncated]" : text;
    }
}
