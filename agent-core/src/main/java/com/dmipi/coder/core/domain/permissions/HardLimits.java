package com.dmipi.coder.core.domain.permissions;

import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Catastrophic actions refused no matter the mode, the rules, or the user's answer — the floor
 * beneath the whole permission layer. Deliberately narrow: it targets a small set of clearly
 * destructive shell commands (recursive root deletion, disk overwrite, filesystem creation,
 * fork bombs), matched on the command line. It is a backstop against accidents, not a sandbox —
 * confinement is the sandbox's job; this only refuses the unmistakably ruinous.
 */
public final class HardLimits {

    private static final List<Pattern> FORBIDDEN_COMMANDS = List.of(
            // rm with a recursive flag — short (-r, -rf, -R) or long (--recursive) — targeting the root.
            Pattern.compile("\\brm\\s+(?:--?[\\w-]+\\s+)*(?:-\\w*[rR]\\w*|--recursive)\\s+(?:--?[\\w-]+\\s+)*[\"']?/+[\"']?(?:\\s|$|\\*)"),
            Pattern.compile("\\bmkfs\\b"),
            Pattern.compile("\\bdd\\b[^\\n]*\\bof=/dev/"),
            Pattern.compile(">\\s*/dev/[sh]d[a-z]"),
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;\\s*:"));

    /** The reason this call is forbidden outright, or empty when it is not. */
    public Optional<String> refusal(final Tool tool, final ToolParams params) {
        if (tool.kind(params) != ToolKind.EXECUTE) {
            return Optional.empty();
        }
        final String command = tool.matchTarget(params);
        for (final Pattern forbidden : FORBIDDEN_COMMANDS) {
            if (forbidden.matcher(command).find()) {
                return Optional.of("The command '" + command + "' is refused as unconditionally destructive (a hard safety limit).");
            }
        }
        return Optional.empty();
    }
}
