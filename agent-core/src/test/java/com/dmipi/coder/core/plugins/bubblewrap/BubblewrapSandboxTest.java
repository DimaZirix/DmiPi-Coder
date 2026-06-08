package com.dmipi.coder.core.plugins.bubblewrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs only where bubblewrap is installed and functional; skipped elsewhere. */
class BubblewrapSandboxTest {

    @TempDir
    private Path projectDirectory;

    @TempDir
    private Path additionalDirectory;

    private final BubblewrapSandboxProvider provider = new BubblewrapSandboxProvider();

    @BeforeEach
    void requireBubblewrap() {
        assumeTrue(provider.available(), "bwrap is not installed on this host");
    }

    @Test
    @DisplayName("the provider passes its conformance probe and the sandbox declares confinement")
    void should_pass_the_conformance_probe() {
        // When
        final Sandbox sandbox = provider.create(spec());

        // Then
        assertThat(sandbox.confines()).isTrue();
        assertThat(sandbox.technology()).isEqualTo("bubblewrap");
    }

    @Test
    @DisplayName("a write inside the project succeeds; a write outside the allowed paths fails")
    void should_confine_writes_to_the_allowed_paths() {
        // Given
        final Sandbox sandbox = provider.create(spec());

        // When
        final ShellResult inside = sandbox.run("echo data > inside.txt", Duration.ofSeconds(10), new CancelToken());
        final ShellResult additional = sandbox.run("echo data > " + additionalDirectory.resolve("extra.txt"), Duration.ofSeconds(10), new CancelToken());
        final ShellResult outside = sandbox.run("touch /usr/.should-not-exist", Duration.ofSeconds(10), new CancelToken());

        // Then
        assertThat(inside.succeeded()).isTrue();
        assertThat(projectDirectory.resolve("inside.txt")).exists();
        assertThat(additional.succeeded()).isTrue();
        assertThat(additionalDirectory.resolve("extra.txt")).exists();
        assertThat(outside.succeeded()).isFalse();
    }

    @Test
    @DisplayName("the private /tmp leaves no trace on the host")
    void should_keep_tmp_private() {
        // Given
        final Sandbox sandbox = provider.create(spec());

        // When: the command writes to /tmp inside the sandbox
        final ShellResult result = sandbox.run("echo x > /tmp/.dmipi-probe-tmp && cat /tmp/.dmipi-probe-tmp", Duration.ofSeconds(10), new CancelToken());

        // Then: it worked inside, and the host's /tmp does not have it
        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).contains("x");
        assertThat(Path.of("/tmp/.dmipi-probe-tmp")).doesNotExist();
    }

    private SandboxSpec spec() {
        return new SandboxSpec(projectDirectory, List.of(additionalDirectory), Duration.ofSeconds(5), Duration.ofSeconds(120));
    }
}
