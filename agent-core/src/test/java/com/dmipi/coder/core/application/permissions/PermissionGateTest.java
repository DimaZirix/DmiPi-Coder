package com.dmipi.coder.core.application.permissions;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.permissions.GateDecision;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import com.dmipi.coder.core.testfixtures.StubTool;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PermissionGateTest {

    private final ToolParams params = new JacksonToolParamsParser(JsonMapper.builder().build()).parse("{}");
    private final Tool askingTool = tool("edit", ToolKind.EDIT, PermissionDecision.ASK);
    private final Tool allowedTool = tool("read", ToolKind.READ, PermissionDecision.ALLOW);

    @Test
    @DisplayName("an ALLOW baseline runs without asking anyone")
    void should_allow_without_asking() {
        // Given
        final ScriptedHil hil = new ScriptedHil(List.of());
        final PermissionGate gate = new PermissionGate(hil, Mode.DEFAULT);

        // When / Then
        assertThat(gate.decide(allowedTool, params)).isInstanceOf(GateDecision.Allowed.class);
        assertThat(hil.asked()).isEmpty();
    }

    @Test
    @DisplayName("an ASK baseline raises a HIL question with the tool's preview; 'allow once' does not stick")
    void should_ask_and_not_remember_a_once_answer() {
        // Given
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("allow-once"), Answer.of("deny")));
        final PermissionGate gate = new PermissionGate(hil, Mode.DEFAULT);

        // When / Then: first call allowed, second call asks again and is denied
        assertThat(gate.decide(askingTool, params)).isInstanceOf(GateDecision.Allowed.class);
        assertThat(gate.decide(askingTool, params)).isInstanceOf(GateDecision.Denied.class);
        assertThat(hil.asked()).hasSize(2);
        assertThat(hil.asked().getFirst().preview()).isEqualTo("stub preview");
    }

    @Test
    @DisplayName("'always allow this session' is remembered by the gate — the second call never asks")
    void should_remember_an_always_answer() {
        // Given
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("allow-always")));
        final PermissionGate gate = new PermissionGate(hil, Mode.DEFAULT);

        // When / Then
        assertThat(gate.decide(askingTool, params)).isInstanceOf(GateDecision.Allowed.class);
        assertThat(gate.decide(askingTool, params)).isInstanceOf(GateDecision.Allowed.class);
        assertThat(hil.asked()).hasSize(1);
    }

    @Test
    @DisplayName("don't-ask mode blocks a call that would ask, with no question")
    void should_block_instead_of_asking_in_dont_ask_mode() {
        // Given
        final ScriptedHil hil = new ScriptedHil(List.of());
        final PermissionGate gate = new PermissionGate(hil, Mode.DONT_ASK);

        // When / Then
        assertThat(gate.decide(askingTool, params)).isInstanceOf(GateDecision.Denied.class);
        assertThat(hil.asked()).isEmpty();
    }

    @Test
    @DisplayName("allow-all mode runs a call that would ask, with no question")
    void should_run_without_asking_in_allow_all_mode() {
        // Given
        final PermissionGate gate = new PermissionGate(new ScriptedHil(List.of()), Mode.ALLOW_ALL);

        // When / Then
        assertThat(gate.decide(askingTool, params)).isInstanceOf(GateDecision.Allowed.class);
    }

    @Test
    @DisplayName("plan mode blocks a mutating call even with an ALLOW baseline; read-only calls pass")
    void should_block_mutations_in_plan_mode() {
        // Given
        final PermissionGate gate = new PermissionGate(new ScriptedHil(List.of()), Mode.PLAN);
        final Tool allowedEdit = tool("edit", ToolKind.EDIT, PermissionDecision.ALLOW);

        // When / Then
        assertThat(gate.decide(allowedEdit, params)).isInstanceOf(GateDecision.Denied.class);
        assertThat(gate.decide(allowedTool, params)).isInstanceOf(GateDecision.Allowed.class);
    }

    @Test
    @DisplayName("allow-edits mode auto-approves an edit that would ask, but a shell-like call still asks")
    void should_auto_approve_only_edits_in_allow_edits_mode() {
        // Given
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("deny")));
        final PermissionGate gate = new PermissionGate(hil, Mode.ALLOW_EDITS);
        final Tool askingShell = tool("run", ToolKind.EXECUTE, PermissionDecision.ASK);

        // When / Then
        assertThat(gate.decide(askingTool, params)).isInstanceOf(GateDecision.Allowed.class);
        assertThat(gate.decide(askingShell, params)).isInstanceOf(GateDecision.Denied.class);
        assertThat(hil.asked()).hasSize(1);
    }

    @Test
    @DisplayName("a plugin policy can tighten ALLOW to DENY, and cannot loosen a DENY baseline")
    void should_compose_policies_tighten_only() {
        // Given
        final PermissionGate gate = new PermissionGate(new ScriptedHil(List.of()), Mode.DEFAULT);
        final Tool denyBaseline = tool("locked", ToolKind.READ, PermissionDecision.DENY);
        gate.registerPolicy(allowedTool, unused -> PermissionDecision.DENY);
        gate.registerPolicy(denyBaseline, unused -> PermissionDecision.ALLOW);

        // When / Then
        assertThat(gate.decide(allowedTool, params)).isInstanceOf(GateDecision.Denied.class);
        assertThat(gate.decide(denyBaseline, params)).isInstanceOf(GateDecision.Denied.class);
    }

    @Test
    @DisplayName("an invalid front-end answer denies the call instead of granting anything")
    void should_deny_on_an_invalid_answer() {
        // Given: an answer outside the offered option ids
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("not-an-option")));
        final PermissionGate gate = new PermissionGate(hil, Mode.DEFAULT);

        // When / Then
        assertThat(gate.decide(askingTool, params)).isInstanceOf(GateDecision.Denied.class);
    }

    @Test
    @DisplayName("the mode is switchable at runtime")
    void should_switch_mode_at_runtime() {
        // Given
        final PermissionGate gate = new PermissionGate(new ScriptedHil(List.of()), Mode.DONT_ASK);

        // When
        gate.switchMode(Mode.ALLOW_ALL);

        // Then
        assertThat(gate.mode()).isEqualTo(Mode.ALLOW_ALL);
        assertThat(gate.decide(askingTool, params)).isInstanceOf(GateDecision.Allowed.class);
    }

    private static Tool tool(final String name, final ToolKind kind, final PermissionDecision baseline) {
        return new StubTool(name, kind, baseline, params -> new ToolResult.Success("ok", new Display.Text("ok")));
    }
}
