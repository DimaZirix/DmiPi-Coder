package com.dmipi.coder.core.plugins.planning;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.hil.Hil;
import com.dmipi.coder.core.domain.hil.Option;
import com.dmipi.coder.core.domain.hil.Question;
import com.dmipi.coder.core.domain.hil.QuestionKind;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.plugin.Modes;
import java.util.List;
import java.util.Optional;

/**
 * Ends plan mode: the agent presents its plan and the user approves or asks for a revision.
 * Approving switches the mode out of plan, unlocking mutations. Main-only and non-mutating, so
 * it is available while plan mode blocks the edit/execute tools.
 */
final class PresentPlanTool implements Tool {

    private static final String APPROVE = "approve";
    private static final String REVISE = "revise";
    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["plan"],
              "properties": {
                "plan": {"type": "string", "description": "The plan to present: what to change, which files, what to reuse, and how to verify."}
              }
            }""";

    private final Hil hil;
    private final Modes modes;

    PresentPlanTool(final Hil hil, final Modes modes) {
        this.hil = hil;
        this.modes = modes;
    }

    @Override
    public String name() {
        return "present_plan";
    }

    @Override
    public String description() {
        return "Presents your plan for the user's approval to leave plan mode. Call it only once your read-only investigation is done and the plan covers what to change, which files, what existing code to reuse, and how to verify. Approval unlocks editing and running commands.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.OTHER;
    }

    @Override
    public boolean mainOnly() {
        return true;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(SCHEMA);
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        if (params.string("plan").filter(plan -> !plan.isBlank()).isEmpty()) {
            return Optional.of("Parameter 'plan' is required — present the plan text.");
        }
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ALLOW;
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        if (modes.current() != Mode.PLAN) {
            return new ToolResult.Failure("Not in plan mode — there is nothing to approve; just do the work.");
        }
        final String plan = params.string("plan").orElseThrow();
        final Question question = new Question(
                "The agent presents a plan. Approve it to leave plan mode?",
                plan,
                QuestionKind.OPTION_LIST,
                List.of(new Option(APPROVE, "Approve — start the work"), new Option(REVISE, "Keep planning")));
        final Answer answer = hil.ask(question);
        if (question.rejection(answer).isEmpty() && answer.selected().getFirst().equals(APPROVE)) {
            modes.switchTo(Mode.DEFAULT);
            return new ToolResult.Success("Plan approved. Plan mode is off — proceed with the work.", new Display.Text("plan approved"));
        }
        return new ToolResult.Success("The user wants to keep planning. Refine the plan and present it again.", new Display.Text("plan not yet approved"));
    }
}
