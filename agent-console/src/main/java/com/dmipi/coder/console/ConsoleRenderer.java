package com.dmipi.coder.console;

import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.event.Out;
import com.dmipi.coder.core.domain.event.OutEvent;
import java.io.PrintWriter;

/**
 * Renders the event stream to a writer, each type in its own style — nothing more. It never
 * interprets, rewrites or reorders; presentation only. Thinking is dimmed; the subagent stream
 * is prefixed so delegated work reads apart from the main conversation.
 */
public final class ConsoleRenderer implements Out {

    /** Whether the writer's cursor sits mid-line — shared across derived renderers, since they share the writer. */
    private static final class LineState {

        private boolean midLine;
    }

    private final PrintWriter writer;
    private final boolean showThinking;
    private final String linePrefix;
    private final LineState state;

    public ConsoleRenderer(final PrintWriter writer) {
        this(writer, false, "", new LineState());
    }

    private ConsoleRenderer(final PrintWriter writer, final boolean showThinking, final String linePrefix, final LineState state) {
        this.writer = writer;
        this.showThinking = showThinking;
        this.linePrefix = linePrefix;
        this.state = state;
    }

    /** A renderer for subagent output — every line prefixed so it reads apart from the main stream. */
    public ConsoleRenderer forSubagents() {
        return new ConsoleRenderer(writer, showThinking, "  │ ", state);
    }

    /** A renderer that also shows dimmed thinking, for a "show thinking" toggle. */
    public ConsoleRenderer withThinking() {
        return new ConsoleRenderer(writer, true, linePrefix, state);
    }

    @Override
    public void event(final OutEvent event) {
        switch (event) {
            case OutEvent.AnswerDelta(final String text) -> write(text);
            case OutEvent.ThinkingDelta(final String text) -> {
                if (showThinking) {
                    write(dim(text));
                }
            }
            case OutEvent.ActivityStarted(final String action, final String summary) -> line("· " + action + (summary.isBlank() ? "" : " " + summary));
            case OutEvent.ActivityFinished(final String action, final Display display) -> renderDisplay(display);
            case OutEvent.ActivityFailed(final String action, final String error) -> line("✗ " + action + ": " + error);
            case OutEvent.TurnStarted() -> {
            }
            case OutEvent.TurnEnded() -> {
                writer.println();
                state.midLine = false;
            }
            case OutEvent.TurnFailed(final String error) -> line("✗ turn failed: " + error);
            case OutEvent.ContextCompacted(final int before, final int after) -> line("(compacted context ~" + before + " → ~" + after + " tokens)");
        }
        writer.flush();
    }

    private void renderDisplay(final Display display) {
        switch (display) {
            case Display.Text(final String text) -> line("  " + text);
            case Display.Diff(final String unifiedDiff) -> renderDiff(unifiedDiff);
            case Display.Todo(final var items) -> items.forEach(item -> line("  " + mark(item.status()) + " " + item.text()));
        }
    }

    private void renderDiff(final String unifiedDiff) {
        for (final String diffLine : unifiedDiff.split("\n", -1)) {
            line("  " + colouredDiffLine(diffLine));
        }
    }

    private static String colouredDiffLine(final String diffLine) {
        if (diffLine.startsWith("+") && !diffLine.startsWith("+++")) {
            return "[32m" + diffLine + "[0m";
        }
        if (diffLine.startsWith("-") && !diffLine.startsWith("---")) {
            return "[31m" + diffLine + "[0m";
        }
        return diffLine;
    }

    private static String mark(final Display.Todo.Status status) {
        return switch (status) {
            case COMPLETED -> "[x]";
            case IN_PROGRESS -> "[~]";
            case PENDING -> "[ ]";
        };
    }

    private static String dim(final String text) {
        return "[2m" + text + "[0m";
    }

    private void line(final String text) {
        if (state.midLine) {
            // Streamed text rarely ends in a newline before an activity arrives; close its line first.
            writer.println();
            state.midLine = false;
        }
        writer.println(linePrefix + text);
    }

    private void write(final String text) {
        if (text.isEmpty()) {
            return;
        }
        final String prefixedAtStart = !state.midLine && !linePrefix.isEmpty() ? linePrefix + text : text;
        writer.print(linePrefix.isEmpty() ? prefixedAtStart : prefixedAfterNewlines(prefixedAtStart));
        state.midLine = !text.endsWith("\n");
    }

    /** Inserts the prefix after every embedded newline, without leaving one dangling after a trailing newline. */
    private String prefixedAfterNewlines(final String text) {
        final boolean endsWithNewline = text.endsWith("\n");
        final String core = endsWithNewline ? text.substring(0, text.length() - 1) : text;
        return core.replace("\n", "\n" + linePrefix) + (endsWithNewline ? "\n" : "");
    }
}
