package com.dmipi.coder.core.domain.permissions;

import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolParams;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One allow/ask/deny rule from settings: a tool-name match (or {@code *} for any) and an
 * optional glob matched against the <em>whole</em> of the call's match target (the command for
 * a shell call, the path for a file call — {@link Tool#matchTarget}, never an abbreviated
 * display line) — {@code ls*} covers {@code ls -la} but not {@code false}. A rule with no
 * argument glob matches every call to the tool.
 */
public record PermissionRule(String toolName, String argumentGlob, PermissionDecision decision) {

    public PermissionRule {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentGlob, "argumentGlob");
        Objects.requireNonNull(decision, "decision");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("A permission rule needs a tool name (or '*').");
        }
    }

    public boolean matches(final Tool tool, final ToolParams params) {
        if (!toolName.equals("*") && !toolName.equals(tool.name())) {
            return false;
        }
        return argumentGlob.isBlank() || globToRegex(argumentGlob).matcher(tool.matchTarget(params)).matches();
    }

    /** A glob where {@code *} is any run of characters; it must match the whole summary. */
    private static Pattern globToRegex(final String glob) {
        final StringBuilder regex = new StringBuilder();
        for (final String literal : glob.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
        }
        return Pattern.compile(regex.toString());
    }
}
