package com.dmipi.coder.core.plugins.podman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PodmanSandboxTest {

    @TempDir
    private Path projectDirectory;

    @TempDir
    private Path additionalDirectory;

    @Test
    @DisplayName("the container argv confines: ephemeral, keep-id, project + extra dirs mounted rw, workdir set, image last")
    void should_build_a_confining_container_command() {
        // Given
        final PodmanSandbox sandbox = new PodmanSandbox(spec(), "example/image:tag");

        // When
        final List<String> argv = sandbox.wrapped("echo hi");

        // Then
        assertThat(argv).startsWith("podman", "run", "--rm", "-i", "--userns=keep-id");
        assertThat(argv).containsSequence("--mount", "type=bind,source=" + projectDirectory + ",destination=" + projectDirectory);
        assertThat(argv).containsSequence("--mount", "type=bind,source=" + additionalDirectory + ",destination=" + additionalDirectory);
        assertThat(argv).containsSequence("--workdir", projectDirectory.toString(), "example/image:tag");
        assertThat(argv).endsWith("/bin/sh", "-c", "echo hi");
    }

    @Test
    @DisplayName("the provider reports podman as a confining technology")
    void should_declare_confinement() {
        final PodmanSandboxProvider provider = new PodmanSandboxProvider();
        assertThat(provider.technology()).isEqualTo("podman");
        assertThat(provider.confines()).isTrue();
    }

    @Test
    @DisplayName("where podman is installed, a trivial command runs confined in the container")
    void should_run_in_a_container_when_available() {
        final PodmanSandboxProvider provider = new PodmanSandboxProvider();
        assumeTrue(provider.available(), "podman is not installed on this host");

        // When
        final Sandbox sandbox = provider.create(spec());
        final ShellResult result = sandbox.run("echo confined", Duration.ofSeconds(60), new CancelToken());

        // Then
        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).contains("confined");
    }

    private SandboxSpec spec() {
        return new SandboxSpec(projectDirectory, List.of(additionalDirectory), Duration.ofSeconds(5), Duration.ofSeconds(120));
    }
}
