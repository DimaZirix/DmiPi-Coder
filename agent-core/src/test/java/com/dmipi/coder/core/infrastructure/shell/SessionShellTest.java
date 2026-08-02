package com.dmipi.coder.core.infrastructure.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionShellTest {

    @TempDir
    private Path project;

    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger closedSandboxes = new AtomicInteger();

    @Test
    @DisplayName("a closed shell refuses further commands instead of silently building a new, untracked sandbox")
    void should_refuse_commands_after_close() {
        // Given: a shell that has run a command and been closed
        final SessionShell shell = new SessionShell(countingProvider(), spec());
        shell.run("noop", new CancelToken());
        shell.close();

        // When / Then: no resurrection — the run is refused, no second sandbox is ever created
        assertThatIllegalStateException()
                .isThrownBy(() -> shell.run("again", new CancelToken()))
                .withMessageContaining("closed");
        assertThat(created).hasValue(1);
        assertThat(closedSandboxes).hasValue(1);
    }

    @Test
    @DisplayName("closing twice tears the sandbox down once")
    void should_close_idempotently() {
        // Given
        final SessionShell shell = new SessionShell(countingProvider(), spec());
        shell.run("noop", new CancelToken());

        // When
        shell.close();
        shell.close();

        // Then
        assertThat(closedSandboxes).hasValue(1);
    }

    private SandboxSpec spec() {
        return new SandboxSpec(project, List.of(), Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private SandboxProvider countingProvider() {
        return new SandboxProvider() {

            @Override
            public String technology() {
                return "counting";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public boolean confines() {
                return false;
            }

            @Override
            public Sandbox create(final SandboxSpec spec) {
                created.incrementAndGet();
                return new Sandbox() {

                    @Override
                    public ShellResult run(final String command, final Duration timeout, final CancelToken cancel) {
                        return new ShellResult(0, "", "", false, false);
                    }

                    @Override
                    public Process startBackground(final String command) {
                        throw new UnsupportedOperationException("not used in this test");
                    }

                    @Override
                    public String technology() {
                        return "counting";
                    }

                    @Override
                    public boolean confines() {
                        return false;
                    }

                    @Override
                    public void close() {
                        closedSandboxes.incrementAndGet();
                    }
                };
            }
        };
    }
}
