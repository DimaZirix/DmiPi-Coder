package com.dmipi.coder.core.plugins.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class DirectSandboxTest {

    @TempDir
    private Path projectDirectory;

    @Test
    @DisplayName("a command runs in the project directory and its output is captured")
    void should_run_a_command_in_the_project_directory() throws IOException {
        // Given / When
        final ShellResult result = sandbox().run("pwd && echo done", Duration.ofSeconds(10), new CancelToken());

        // Then
        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).contains(projectDirectory.toRealPath().toString()).contains("done");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    @DisplayName("exit code and stderr are reported separately from stdout")
    void should_report_exit_code_and_stderr() {
        // Given / When
        final ShellResult result = sandbox().run("echo out; echo err >&2; exit 3", Duration.ofSeconds(10), new CancelToken());

        // Then
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.succeeded()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.stdout()).isEqualToIgnoringNewLines("out");
        assertThat(result.stderr()).isEqualToIgnoringNewLines("err");
    }

    @Test
    @DisplayName("a command exceeding its timeout is killed with its whole process tree, promptly")
    void should_kill_a_timed_out_command() {
        // Given
        final Instant start = Instant.now();

        // When: the command would run for 30s, the budget is 200ms
        final ShellResult result = sandbox().run("sleep 30", Duration.ofMillis(200), new CancelToken());

        // Then: reported as timed out, and the wait ended near the budget, not the sleep
        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("cancellation tears the command down without calling it a timeout")
    void should_stop_a_cancelled_command() {
        // Given
        final CancelToken cancel = new CancelToken();
        cancel.cancel();
        final Instant start = Instant.now();

        // When
        final ShellResult result = sandbox().run("sleep 30", Duration.ofSeconds(60), cancel);

        // Then
        assertThat(result.timedOut()).isFalse();
        assertThat(result.succeeded()).isFalse();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("output that was produced before a kill is still reported")
    void should_keep_output_produced_before_a_kill() {
        // When: prints, then overruns
        final ShellResult result = sandbox().run("echo early; sleep 30", Duration.ofMillis(300), new CancelToken());

        // Then
        assertThat(result.timedOut()).isTrue();
        assertThat(result.stdout()).contains("early");
    }

    private Sandbox sandbox() {
        return new DirectSandboxProvider().create(
                new SandboxSpec(projectDirectory, List.of(), Duration.ofSeconds(5), Duration.ofSeconds(120)));
    }
}
