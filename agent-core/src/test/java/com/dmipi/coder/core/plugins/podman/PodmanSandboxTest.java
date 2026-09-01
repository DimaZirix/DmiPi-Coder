package com.dmipi.coder.core.plugins.podman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.ResourceLimits;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxNetwork;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PodmanSandboxTest {

    @TempDir
    private Path projectDirectory;

    @TempDir
    private Path additionalDirectory;

    @Test
    @DisplayName("the container argv confines: ephemeral, keep-id, no-new-privileges, project + extra dirs mounted rw, workdir set, image last")
    void should_build_a_confining_container_command() {
        // Given
        final PodmanSandbox sandbox = sandbox(spec(), ResourceLimits.none(), null);

        // When
        final List<String> argv = sandbox.wrapped("echo hi", Duration.ZERO);

        // Then
        assertThat(argv).startsWith("podman", "run", "--rm", "-i", "--userns=keep-id", "--security-opt=no-new-privileges");
        assertThat(argv).containsSequence("--mount", "type=bind,source=" + projectDirectory + ",destination=" + projectDirectory);
        assertThat(argv).containsSequence("--mount", "type=bind,source=" + additionalDirectory + ",destination=" + additionalDirectory);
        assertThat(argv).containsSequence("--workdir", projectDirectory.toString(), "example/image:tag");
        assertThat(argv).endsWith("/bin/sh", "-c", "echo hi");
    }

    @Test
    @DisplayName("the foreground timeout is enforced inside the boundary too — the host-side kill cannot reach conmon-supervised processes")
    void should_carry_the_timeout_into_the_container() {
        // Given
        final PodmanSandbox sandbox = sandbox(spec(), ResourceLimits.none(), null);

        // When / Then: rounded up to whole seconds; absent for the unbounded background case
        assertThat(sandbox.wrapped("echo hi", Duration.ofMillis(1_500))).containsSequence("--timeout", "2");
        assertThat(sandbox.wrapped("echo hi", Duration.ZERO)).doesNotContain("--timeout");
    }

    @Test
    @DisplayName("resource limits become podman's own cgroup flags")
    void should_translate_resource_limits_into_cgroup_flags() {
        // Given
        final PodmanSandbox limited = sandbox(spec(), new ResourceLimits("512M", 64), null);
        final PodmanSandbox unlimited = sandbox(spec(), ResourceLimits.none(), null);

        // When / Then
        assertThat(limited.wrapped("echo hi", Duration.ZERO)).containsSequence("--memory", "512M");
        assertThat(limited.wrapped("echo hi", Duration.ZERO)).containsSequence("--pids-limit", "64");
        assertThat(unlimited.wrapped("echo hi", Duration.ZERO)).doesNotContain("--memory", "--pids-limit");
    }

    @Test
    @DisplayName("an isolated network becomes --network=none")
    void should_translate_an_isolated_network() {
        final PodmanSandbox isolated = sandbox(spec().withNetwork(new SandboxNetwork.Isolated()), ResourceLimits.none(), null);
        assertThat(isolated.wrapped("echo hi", Duration.ZERO)).contains("--network=none");
    }

    @Test
    @DisplayName("a proxied network rides the selected helper's netmode, blackholes DNS, and points the proxy env at the mapped host loopback")
    void should_translate_a_proxied_network() {
        // Given
        final SandboxSpec proxied = spec().withNetwork(new SandboxNetwork.Proxied(8081, "tok"));

        // When
        final List<String> pasta = sandbox(proxied, ResourceLimits.none(), new ProxyRoute(ProxyNetwork.PASTA, "10.7.8.2")).wrapped("echo hi", Duration.ZERO);
        final List<String> slirp = sandbox(proxied, ResourceLimits.none(), new ProxyRoute(ProxyNetwork.SLIRP4NETNS, "10.7.8.2")).wrapped("echo hi", Duration.ZERO);

        // Then: pasta maps the address directly; slirp derives its subnet from it — the proxy URL is the same for both
        assertThat(pasta).contains("--network=pasta:--map-host-loopback,10.7.8.2");
        assertThat(slirp).contains("--network=slirp4netns:allow_host_loopback=true,cidr=10.7.8.0/24");
        assertThat(pasta).containsSequence("--dns", "127.0.0.1");
        assertThat(pasta).containsSequence("-e", "HTTPS_PROXY=http://coder:tok@10.7.8.2:8081");
        assertThat(slirp).containsSequence("-e", "HTTPS_PROXY=http://coder:tok@10.7.8.2:8081");
    }

    @Test
    @DisplayName("the netmode autoselect prefers pasta, falls back to slirp4netns, and is empty when neither helper exists")
    void should_autoselect_the_loopback_exposing_helper() {
        assertThat(ProxyNetwork.autoSelect(Set.of("pasta", "slirp4netns")::contains)).contains(ProxyNetwork.PASTA);
        assertThat(ProxyNetwork.autoSelect(Set.of("slirp4netns")::contains)).contains(ProxyNetwork.SLIRP4NETNS);
        assertThat(ProxyNetwork.autoSelect(Set.of("pasta")::contains)).contains(ProxyNetwork.PASTA);
        assertThat(ProxyNetwork.autoSelect(executable -> false)).isEmpty();
    }

    @Test
    @DisplayName("the per-session host-loopback address is always a 10.x.y.2 — the shape both netmodes can serve")
    void should_generate_a_mappable_host_loopback_address() {
        final Random random = new Random(42);
        for (int i = 0; i < 100; i++) {
            assertThat(ProxyNetwork.randomHostLoopback(random)).matches("10\\.\\d{1,3}\\.\\d{1,3}\\.2");
        }
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

    private static PodmanSandbox sandbox(final SandboxSpec spec, final ResourceLimits limits, final ProxyRoute proxyRoute) {
        return new PodmanSandbox(spec, "example/image:tag", limits, proxyRoute);
    }

    private SandboxSpec spec() {
        return new SandboxSpec(projectDirectory, List.of(additionalDirectory), Duration.ofSeconds(5), Duration.ofSeconds(120));
    }
}
