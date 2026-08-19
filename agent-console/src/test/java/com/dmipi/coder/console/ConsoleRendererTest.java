package com.dmipi.coder.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.event.OutEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsoleRendererTest {

    private final StringWriter buffer = new StringWriter();
    private final ConsoleRenderer renderer = new ConsoleRenderer(new PrintWriter(buffer));

    @Test
    @DisplayName("answer text streams verbatim; a todo display renders status marks")
    void should_render_answer_and_todo() {
        // When
        renderer.event(new OutEvent.AnswerDelta("Here is the plan."));
        renderer.event(new OutEvent.ActivityFinished("todo_write", new Display.Todo(List.of(
                new Display.Todo.Item("Read the file", Display.Todo.Status.COMPLETED),
                new Display.Todo.Item("Fix the bug", Display.Todo.Status.IN_PROGRESS)))));

        // Then
        assertThat(buffer.toString())
                .contains("Here is the plan.")
                .contains("[x] Read the file")
                .contains("[~] Fix the bug");
    }

    @Test
    @DisplayName("thinking is hidden by default and dimmed when toggled on")
    void should_gate_thinking_behind_a_toggle() {
        // When
        renderer.event(new OutEvent.ThinkingDelta("pondering"));
        renderer.withThinking().event(new OutEvent.ThinkingDelta("out loud"));

        // Then
        assertThat(buffer.toString()).doesNotContain("pondering").contains("out loud");
    }

    @Test
    @DisplayName("with ANSI off, diffs and thinking render as plain text — no escape bytes for pipes")
    void should_render_plainly_without_ansi() {
        // Given a renderer wired for piped output
        final ConsoleRenderer plain = new ConsoleRenderer(new PrintWriter(buffer), false);

        // When
        plain.event(new OutEvent.ActivityFinished("edit", new Display.Diff("-old\n+new")));
        plain.withThinking().event(new OutEvent.ThinkingDelta("pondering"));

        // Then
        assertThat(buffer.toString()).contains("-old").contains("+new").contains("pondering").doesNotContain("\u001b");
    }

    @Test
    @DisplayName("an activity after unterminated streamed text starts on its own line, never glued")
    void should_break_the_line_before_an_activity() {
        // Given / When: streamed text with no trailing newline, then a tool call begins
        renderer.event(new OutEvent.AnswerDelta("Let me check."));
        renderer.event(new OutEvent.ActivityStarted("read_file", "app.yaml"));

        // Then
        assertThat(buffer.toString()).contains("Let me check.\n· read_file app.yaml");
    }

    @Test
    @DisplayName("the first streamed subagent line carries the prefix, like every later one")
    void should_prefix_the_first_subagent_line() {
        // Given / When: a subagent answer spanning two lines, the first without any preceding event
        renderer.forSubagents().event(new OutEvent.AnswerDelta("first line\nsecond line"));

        // Then
        assertThat(buffer.toString()).startsWith("  │ first line\n  │ second line");
    }

    @Test
    @DisplayName("the subagent renderer prefixes its lines so delegated work reads apart")
    void should_prefix_subagent_output() {
        // When
        renderer.forSubagents().event(new OutEvent.ActivityStarted("read_file", "app.yaml"));

        // Then
        assertThat(buffer.toString()).contains("  │ · read_file app.yaml");
    }

    @Test
    @DisplayName("a failed activity is marked, and compaction is surfaced")
    void should_surface_failures_and_housekeeping() {
        // When
        renderer.event(new OutEvent.ActivityFailed("run_shell_command", "exit 1"));
        renderer.event(new OutEvent.ContextCompacted(1000, 300));

        // Then
        assertThat(buffer.toString())
                .contains("✗ run_shell_command: exit 1")
                .contains("compacted context ~1000 → ~300 tokens");
    }
}
