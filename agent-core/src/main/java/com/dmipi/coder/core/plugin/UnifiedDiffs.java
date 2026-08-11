package com.dmipi.coder.core.plugin;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import java.util.List;

/** Unified diffs for previews and displays. */
public final class UnifiedDiffs {

    private static final int CONTEXT_LINES = 3;

    private UnifiedDiffs() {
    }

    /** The unified diff between two contents of the named file; empty when nothing changes. */
    public static String between(final String path, final String before, final String after) {
        final List<String> originalLines = before.lines().toList();
        final List<String> revisedLines = after.lines().toList();
        final Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);
        if (patch.getDeltas().isEmpty()) {
            return "";
        }
        return String.join("\n", UnifiedDiffUtils.generateUnifiedDiff(path, path, originalLines, patch, CONTEXT_LINES));
    }
}
