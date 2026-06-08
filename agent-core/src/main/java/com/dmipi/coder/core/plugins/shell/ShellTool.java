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
        final Optional<Duration> timeout = params.integer("timeout_seconds").map(Duration::ofSeconds);
        final ShellResult result;
        try {
            result = shell.run(params.string("command").orElseThrow(), timeout, cancel);
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure("The command could not be run: " + failure.getMessage());
        }
        return report(result);
    }

    private static ToolResult report(final ShellResult result) {
        final String body = compose(result);
        if (result.timedOut()) {
            return new ToolResult.Failure("The command was killed for exceeding its timeout.\n" + body);
        }
        if (!result.succeeded()) {
            return new ToolResult.Failure("Exit code " + result.exitCode() + ".\n" + body);
        }
        return new ToolResult.Success(body.isBlank() ? "(exit 0, no output)" : body, new Display.Text("exit 0"));
    }

    private static String compose(final ShellResult result) {
        final StringBuilder body = new StringBuilder();
        appendCapped(body, result.stdout());
        if (!result.stderr().isBlank()) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append("[stderr]\n");
            appendCapped(body, result.stderr());
        }
        return body.toString();
    }

    private static void appendCapped(final StringBuilder body, final String text) {
        if (text.length() > OUTPUT_CAP) {
            body.append(text, 0, OUTPUT_CAP).append("\n[…output truncated]");
        } else {
            body.append(text);
        }
    }
}
