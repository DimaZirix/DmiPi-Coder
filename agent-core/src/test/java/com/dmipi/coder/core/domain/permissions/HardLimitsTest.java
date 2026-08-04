package com.dmipi.coder.core.domain.permissions;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HardLimitsTest {

    private final HardLimits limits = new HardLimits();

    @ParameterizedTest(name = "refuses: {0}")
    @DisplayName("every spelling of the catastrophic commands is refused, naming the command")
    @ValueSource(strings = {
            "rm -rf /",
            "rm -fr /",
            "rm -r -f /",
            "rm -R /",
            "rm --recursive /",
            "rm --recursive --force /",
            "rm -r --force /",
            "rm --force --recursive /",
            "sudo rm -rf /*",
            "mkfs.ext4 /dev/sda1",
            "dd if=/dev/zero of=/dev/sda",
            "echo x > /dev/sda",
            ":(){ :|:& };:"})
    void should_refuse_catastrophic_commands(final String command) {
        // When
        final Optional<String> refusal = limits.refusal(shell(), params(command));

        // Then
        assertThat(refusal).hasValueSatisfying(reason -> assertThat(reason).contains(command.contains(">") ? "/dev/sd" : command.split(" ")[0]));
    }

    @ParameterizedTest(name = "allows: {0}")
    @DisplayName("ordinary and near-miss commands pass the backstop")
    @ValueSource(strings = {
            "rm -rf ./build",
            "rm -rf /home/user/project",
            "rm notes.txt",
            "rm --force notes.txt",
            "firm -rf /",
            "confirm",
            "echo rm -rf is dangerous"})
    void should_pass_ordinary_commands(final String command) {
        assertThat(limits.refusal(shell(), params(command))).isEmpty();
    }

    @Test
    @DisplayName("screening reads the match target, not the display summary — an abbreviating tool cannot evade the floor")
    void should_screen_the_match_target_not_the_abbreviated_summary() {
        // Given: an EXECUTE tool whose display line abbreviates while the real command is catastrophic
        final Tool abbreviating = new Tool() {

            @Override
            public String name() {
                return "run_shell_command";
            }

            @Override
            public String description() {
                return "stub";
            }

            @Override
            public ToolKind kind() {
                return ToolKind.EXECUTE;
            }

            @Override
            public ParameterSchema parameterSchema() {
                return new ParameterSchema("{}");
            }

            @Override
            public Optional<String> validate(final ToolParams params) {
                return Optional.empty();
            }

            @Override
            public PermissionDecision defaultPermission(final ToolParams params) {
                return PermissionDecision.ASK;
            }

            @Override
            public String callSummary(final ToolParams params) {
                return "rm …";
            }

            @Override
            public String matchTarget(final ToolParams params) {
                return "rm -rf /";
            }

            @Override
            public ToolResult execute(final ToolParams params, final CancelToken cancel) {
                return new ToolResult.Failure("never runs in this test");
            }
        };

        // When / Then
        assertThat(limits.refusal(abbreviating, params("ignored"))).isPresent();
    }

    @Test
    @DisplayName("only EXECUTE calls are screened — a read tool with a hostile summary passes")
    void should_screen_only_execute_calls() {
        // Given: a READ tool whose summary happens to contain a forbidden command
        final Tool read = tool("read", ToolKind.READ, "rm -rf /");

        // When / Then
        assertThat(limits.refusal(read, params("ignored"))).isEmpty();
    }

    private Tool shell() {
        return tool("run_shell_command", ToolKind.EXECUTE, null);
    }

    private ToolParams params(final String command) {
        return new ToolParams() {

            @Override
            public Optional<String> string(final String key) {
                return "command".equals(key) ? Optional.of(command) : Optional.empty();
            }

            @Override
            public Optional<Long> integer(final String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> bool(final String key) {
                return Optional.empty();
            }

            @Override
            public Optional<List<String>> stringList(final String key) {
                return Optional.empty();
            }

            @Override
            public String rawJson() {
                return "{}";
            }
        };
    }

    /** A stub whose summary is the command param, or a fixed summary when given. */
    private static Tool tool(final String name, final ToolKind kind, final String fixedSummary) {
        return new Tool() {

            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "stub";
            }

            @Override
            public ToolKind kind() {
                return kind;
            }

            @Override
            public ParameterSchema parameterSchema() {
                return new ParameterSchema("{}");
            }

            @Override
            public Optional<String> validate(final ToolParams params) {
                return Optional.empty();
            }

            @Override
            public PermissionDecision defaultPermission(final ToolParams params) {
                return PermissionDecision.ASK;
            }

            @Override
            public String callSummary(final ToolParams params) {
                return fixedSummary != null ? fixedSummary : params.string("command").orElse("");
            }

            @Override
            public ToolResult execute(final ToolParams params, final CancelToken cancel) {
                return new ToolResult.Failure("never runs in this test");
            }
        };
    }
}
