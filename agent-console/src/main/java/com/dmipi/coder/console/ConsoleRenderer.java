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

    private final PrintWriter writer;
    private final boolean showThinking;
    private final String linePrefix;

    public ConsoleRenderer(final PrintWriter writer) {
        this(writer, false, "");
    }

    private ConsoleRenderer(final PrintWriter writer, final boolean showThinking, final String linePrefix) {
        this.writer = writer;
        this.showThinking = showThinking;
        this.linePrefix = linePrefix;
    }

    /** A renderer for subagent output — every line prefixed so it reads apart from the main stream. */
    public ConsoleRenderer forSubagents() {
        return new ConsoleRenderer(writer, showThinking, "  │ ");
    }

    /** A renderer that also shows dimmed thinking, for a "show thinking" toggle. */
    public ConsoleRenderer withThinking() {
        return new ConsoleRenderer(writer, true, linePrefix);
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
            case OutEvent.TurnEnded() -> writer.println();
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
        writer.println(linePrefix + text);
    }

    private void write(final String text) {
        writer.print(linePrefix.isEmpty() ? text : text.replace("\n", "\n" + linePrefix));
    }
}
