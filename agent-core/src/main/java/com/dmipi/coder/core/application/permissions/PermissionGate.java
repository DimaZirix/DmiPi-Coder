package com.dmipi.coder.core.application.permissions;

import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.hil.Hil;
import com.dmipi.coder.core.domain.hil.Option;
import com.dmipi.coder.core.domain.hil.Question;
import com.dmipi.coder.core.domain.hil.QuestionKind;
import com.dmipi.coder.core.domain.permissions.GateDecision;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.permissions.PermissionPolicy;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolGate;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The permission layer: non-removable, interposed on every call. Composes the tool's baseline
 * with a plugin policy (tighten-only), applies the mode's ask outcome, remembers "always this
 * session" answers, and asks the human via HIL with the call's preview. A mode never overrides
 * a DENY.
 */
public final class PermissionGate implements ToolGate {

    private static final String ALLOW_ONCE = "allow-once";
    private static final String ALLOW_ALWAYS = "allow-always";
    private static final String DENY = "deny";

    private final Hil hil;
    private final SessionApprovals approvals = new SessionApprovals();
    private final Map<Tool, PermissionPolicy> policies = new IdentityHashMap<>();
    private volatile Mode mode;

    public PermissionGate(final Hil hil, final Mode mode) {
        this.hil = Objects.requireNonNull(hil, "hil");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public Mode mode() {
        return mode;
    }

    public void switchMode(final Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /** Attaches a plugin's policy to its tool; the policy can only tighten the tool's baseline. */
    public void registerPolicy(final Tool tool, final PermissionPolicy policy) {
        policies.put(tool, policy);
    }

    @Override
    public GateDecision decide(final Tool tool, final ToolParams params) {
        if (mode == Mode.PLAN && tool.kind().mutates()) {
            return new GateDecision.Denied("Plan mode is active: mutating calls are blocked until the plan is approved.");
        }

        final PermissionDecision decision = softened(tool, composed(tool, params));
        return switch (decision) {
            case ALLOW -> new GateDecision.Allowed();
            case DENY -> new GateDecision.Denied("The call is denied by policy.");
            case ASK -> onAsk(tool, params);
        };
    }

    private PermissionDecision composed(final Tool tool, final ToolParams params) {
        final PermissionDecision baseline = tool.defaultPermission(params);
        final PermissionPolicy policy = policies.get(tool);
        if (policy == null) {
            return baseline;
        }
        return baseline.tightenedBy(policy.decision(params));
    }

    /** Allow-edits auto-approves an edit that would only have asked; it never touches a DENY. */
    private PermissionDecision softened(final Tool tool, final PermissionDecision decision) {
        if (mode == Mode.ALLOW_EDITS && tool.kind() == ToolKind.EDIT && decision == PermissionDecision.ASK) {
            return PermissionDecision.ALLOW;
        }
        return decision;
    }

    private GateDecision onAsk(final Tool tool, final ToolParams params) {
        return switch (mode.askOutcome()) {
            case RUN -> new GateDecision.Allowed();
            case BLOCK -> new GateDecision.Denied("Don't-ask mode is active: a call that would ask is blocked instead.");
            case PROMPT -> askHuman(tool, params);
        };
    }

    private GateDecision askHuman(final Tool tool, final ToolParams params) {
        if (approvals.isApproved(tool.name())) {
            return new GateDecision.Allowed();
        }

        final Question question = permissionQuestion(tool, params);
        final Answer answer = hil.ask(question);
        if (question.rejection(answer).isPresent()) {
            return new GateDecision.Denied("The front-end returned an invalid answer to the permission question: " + question.rejection(answer).orElseThrow());
        }

        return switch (answer.selected().getFirst()) {
            case ALLOW_ONCE -> new GateDecision.Allowed();
            case ALLOW_ALWAYS -> {
                approvals.approve(tool.name());
                yield new GateDecision.Allowed();
            }
            default -> new GateDecision.Denied("The user denied the call.");
        };
    }

    private static Question permissionQuestion(final Tool tool, final ToolParams params) {
        final String summary = tool.callSummary(params);
        final String subject = summary.isBlank() ? tool.name() : tool.name() + " — " + summary;
        return new Question(
                "Allow the agent to run '" + subject + "'?",
                tool.preview(params),
                QuestionKind.OPTION_LIST,
                List.of(new Option(ALLOW_ONCE, "Allow once"), new Option(ALLOW_ALWAYS, "Always allow this session"), new Option(DENY, "Deny")));
    }
}
