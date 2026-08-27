package com.dmipi.coder.core.infrastructure.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dmipi.coder.core.application.egress.EgressPolicy;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxNetwork;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    @DisplayName("with an egress policy, the proxy starts with the sandbox, resolves the spec, and dies with the session")
    void should_share_the_proxy_lifecycle_with_the_session() throws IOException {
        // Given: a provider that captures the spec it is handed
        final AtomicReference<SandboxSpec> created = new AtomicReference<>();
        final EgressPolicy policy = new EgressPolicy(List.of(), question -> {
            throw new AssertionError("no question expected in allow-all mode");
        }, () -> Mode.ALLOW_ALL);
        final SessionShell shell = new SessionShell(capturingProvider(created), spec(), policy);

        // When
        shell.run("noop", new CancelToken());

        // Then: the provider received a proxied contract and the proxy is really listening
        assertThat(created.get().network()).isInstanceOf(SandboxNetwork.Proxied.class);
        final SandboxNetwork.Proxied proxied = (SandboxNetwork.Proxied) created.get().network();
        assertThat(proxied.token()).isNotBlank();
        try (Socket connection = new Socket(InetAddress.getLoopbackAddress(), proxied.port())) {
            assertThat(connection.isConnected()).isTrue();
        }

        // When / Then: closing the session closes the proxy with it
        shell.close();
        assertThatThrownBy(() -> new Socket(InetAddress.getLoopbackAddress(), proxied.port()).close()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("without an egress policy, the provider receives the spec's own network untouched")
    void should_leave_the_network_untouched_without_a_policy() {
        // Given
        final AtomicReference<SandboxSpec> created = new AtomicReference<>();
        final SessionShell shell = new SessionShell(capturingProvider(created), spec());

        // When
        shell.run("noop", new CancelToken());

        // Then
        assertThat(created.get().network()).isInstanceOf(SandboxNetwork.Open.class);
        shell.close();
    }

    private SandboxSpec spec() {
        return new SandboxSpec(project, List.of(), Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private SandboxProvider capturingProvider(final AtomicReference<SandboxSpec> created) {
        final SandboxProvider counting = countingProvider();
        return new SandboxProvider() {

            @Override
            public String technology() {
                return counting.technology();
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public boolean confines() {
                return true;
            }

            @Override
            public Sandbox create(final SandboxSpec spec) {
                created.set(spec);
                return counting.create(spec);
            }
        };
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
