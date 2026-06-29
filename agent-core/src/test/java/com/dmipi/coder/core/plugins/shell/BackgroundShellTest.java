package com.dmipi.coder.core.plugins.shell;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import com.dmipi.coder.core.infrastructure.shell.SessionShell;
import com.dmipi.coder.core.plugins.sandbox.DirectSandboxProvider;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class BackgroundShellTest {

    @TempDir
    private Path project;

    private final JacksonToolParamsParser parser = new JacksonToolParamsParser(tools.jackson.databind.json.JsonMapper.builder().build());

    @Test
    @DisplayName("with background enabled, is_background starts a process that is killed at session close")
    void should_start_and_stop_a_background_process() {
        // Given: a background-enabled shell tool over a real direct sandbox
        final SessionShell shell = new SessionShell(new DirectSandboxProvider(), new SandboxSpec(project, List.of(), Duration.ofSeconds(5), Duration.ofSeconds(60)));
        final ShellTool tool = new ShellTool(shell, true);

        // When: start a marker file writer that would run for a while, in the background
        final ToolResult started = tool.execute(params("{\"command\": \"sleep 30; touch should_not_exist\", \"is_background\": true}"), new CancelToken());

        // Then: it returns immediately with a handle
        assertThat(started).isInstanceOf(ToolResult.Success.class);
        assertThat(started.llmContent()).contains("Started in the background");

        // When: the session closes
        shell.close();

        // Then: the background process was killed, so its delayed side effect never happened
        assertThat(project.resolve("should_not_exist")).doesNotExist();
    }

    @Test
    @DisplayName("without background enabled, the tool schema has no is_background parameter")
    void should_hide_the_parameter_when_disabled() {
        final SessionShell shell = new SessionShell(new DirectSandboxProvider(), new SandboxSpec(project, List.of(), Duration.ofSeconds(5), Duration.ofSeconds(60)));
        assertThat(new ShellTool(shell, false).parameterSchema().json()).doesNotContain("is_background");
        assertThat(new ShellTool(shell, true).parameterSchema().json()).contains("is_background");
    }

    private ToolParams params(final String json) {
        return parser.parse(json);
    }
}
