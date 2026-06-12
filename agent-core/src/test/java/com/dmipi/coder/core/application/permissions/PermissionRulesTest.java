package com.dmipi.coder.core.application.permissions;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.permissions.GateDecision;
import com.dmipi.coder.core.domain.permissions.HardLimits;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.permissions.PermissionRule;
import com.dmipi.coder.core.domain.permissions.PermissionRules;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PermissionRulesTest {

    private final JacksonToolParamsParser parser = new JacksonToolParamsParser(JsonMapper.builder().build());
    private final Tool shell = new ShellStub();

    @Test
    @DisplayName("an allow rule converts an ask into a run without prompting the human")
    void should_auto_allow_a_matching_call() {
        // Given: a shell tool that would ask, and a rule allowing `git status`
        final PermissionRules rules = new PermissionRules(List.of(new PermissionRule("run_shell_command", "git status", PermissionDecision.ALLOW)));
        final ScriptedHil hil = new ScriptedHil(List.of());
        final PermissionGate gate = new PermissionGate(hil, Mode.DEFAULT, rules, new HardLimits());

        // When
        final GateDecision decision = gate.decide(shell, params("git status"));

        // Then
        assertThat(decision).isInstanceOf(GateDecision.Allowed.class);
        assertThat(hil.asked()).isEmpty();
    }

    @Test
    @DisplayName("a deny rule blocks the call in every mode, even allow-all")
    void should_deny_a_matching_call_in_every_mode() {
        // Given
        final PermissionRules rules = new PermissionRules(List.of(new PermissionRule("run_shell_command", "curl *", PermissionDecision.DENY)));
        final PermissionGate gate = new PermissionGate(new ScriptedHil(List.of()), Mode.ALLOW_ALL, rules, new HardLimits());

        // When
        final GateDecision decision = gate.decide(shell, params("curl http://evil"));

        // Then
        assertThat(decision).isInstanceOf(GateDecision.Denied.class);
    }

    @Test
    @DisplayName("deny wins over allow when both rules match")
    void should_prefer_deny_over_allow() {
        // Given: an allow-all rule and a specific deny
        final PermissionRules rules = new PermissionRules(List.of(
                new PermissionRule("run_shell_command", "", PermissionDecision.ALLOW),
                new PermissionRule("run_shell_command", "rm *", PermissionDecision.DENY)));
        final PermissionGate gate = new PermissionGate(new ScriptedHil(List.of()), Mode.DEFAULT, rules, new HardLimits());

        // When / Then
        assertThat(gate.decide(shell, params("rm notes.txt"))).isInstanceOf(GateDecision.Denied.class);
        assertThat(gate.decide(shell, params("ls"))).isInstanceOf(GateDecision.Allowed.class);
    }

    @Test
    @DisplayName("a hard limit refuses a catastrophic command past any allow rule or mode")
    void should_enforce_hard_limits_over_everything() {
        // Given: allow-all mode AND an explicit allow rule — the hard limit still refuses
        final PermissionRules rules = new PermissionRules(List.of(new PermissionRule("*", "", PermissionDecision.ALLOW)));
        final PermissionGate gate = new PermissionGate(new ScriptedHil(List.of()), Mode.ALLOW_ALL, rules, new HardLimits());

        // When / Then
        assertThat(gate.decide(shell, params("rm -rf /"))).isInstanceOf(GateDecision.Denied.class);
        assertThat(gate.decide(shell, params("rm -rf /home/user/project"))).isInstanceOf(GateDecision.Allowed.class);
    }

    @Test
    @DisplayName("with no rule matching, the tool baseline and mode decide as before")
    void should_fall_through_when_no_rule_matches() {
        // Given
        final PermissionGate gate = new PermissionGate(new ScriptedHil(List.of(Answer.of("allow-once"))), Mode.DEFAULT, PermissionRules.none(), new HardLimits());

        // When: an EXECUTE tool with an ASK baseline and no rule asks the human
        final GateDecision decision = gate.decide(shell, params("echo hi"));

        // Then
        assertThat(decision).isInstanceOf(GateDecision.Allowed.class);
    }

    private ToolParams params(final String command) {
        return parser.parse("{\"command\": \"" + command + "\"}");
    }

    /** A shell-like tool: EXECUTE, ASK baseline, its summary is the command — what rules and limits match on. */
    private static final class ShellStub implements Tool {

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
            return new ParameterSchema("{\"type\": \"object\"}");
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
            return params.string("command").orElse("");
        }

        @Override
        public ToolResult execute(final ToolParams params, final CancelToken cancel) {
            return new ToolResult.Success("ran", new com.dmipi.coder.core.domain.event.Display.Text("ran"));
        }
    }
}
