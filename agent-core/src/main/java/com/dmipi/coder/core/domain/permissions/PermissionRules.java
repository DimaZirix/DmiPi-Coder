package com.dmipi.coder.core.domain.permissions;

import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolParams;
import java.util.List;
import java.util.Optional;

/**
 * The operator's allow/ask/deny rules for a call. Among the matching rules the strictest
 * decision holds: deny wins over ask wins over allow — a broad ask rule is not silenced by a
 * narrower allow, and an explicit deny beats both. No rule matches → empty, and the gate falls
 * through to baselines and the mode.
 */
public final class PermissionRules {

    private final List<PermissionRule> rules;

    public PermissionRules(final List<PermissionRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static PermissionRules none() {
        return new PermissionRules(List.of());
    }

    public Optional<PermissionDecision> decisionFor(final Tool tool, final ToolParams params) {
        return rules.stream()
                .filter(rule -> rule.matches(tool, params))
                .map(PermissionRule::decision)
                .reduce(PermissionDecision::tightenedBy);
    }
}
