package com.dmipi.coder.core.plugins.bubblewrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxNetwork;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import com.dmipi.coder.core.infrastructure.shell.ProcessRunner;
import com.dmipi.coder.core.infrastructure.shell.egress.EgressProxy;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    @Test
    @DisplayName("resource limits prefix the argv with a systemd-run scope; without limits, bwrap comes first")
    void should_prefix_the_argv_with_systemd_run_only_when_limits_are_bounded() {
        // Given
        final BubblewrapSandbox limited = new BubblewrapSandbox(spec(), new ResourceLimits("512M", 64));
        final BubblewrapSandbox unlimited = new BubblewrapSandbox(spec(), ResourceLimits.none());

        // When / Then
        assertThat(limited.wrapped("echo hi")).startsWith("systemd-run", "--user", "--scope", "--quiet", "-p", "MemoryMax=512M", "-p", "TasksMax=64", "bwrap");
        assertThat(unlimited.wrapped("echo hi")).startsWith("bwrap");
    }

    @Test
    @DisplayName("a command exceeding MemoryMax fails in a bounded sandbox; the same command succeeds unbounded")
    void should_enforce_the_memory_limit_through_systemd_run() {
        // Given
        assumeTrue(systemdUserScopeWorks(), "systemd-run --user --scope does not work on this host");
        final Sandbox bounded = new BubblewrapSandboxProvider(new ResourceLimits("32M", 0)).create(spec());
        final Sandbox unbounded = provider.create(spec());
        final String memoryHog = "head -c 64M /dev/zero | tail -c 64M > /dev/null";

        // When
        final ShellResult hogBounded = bounded.run(memoryHog, Duration.ofSeconds(30), new CancelToken());
        final ShellResult hogUnbounded = unbounded.run(memoryHog, Duration.ofSeconds(30), new CancelToken());

        // Then
        assertThat(hogBounded.succeeded()).isFalse();
        assertThat(hogUnbounded.succeeded()).isTrue();
    }

    @Test
    @DisplayName("a write to the user's home — writable on the host — fails inside the sandbox")
    void should_refuse_a_write_to_the_users_home() {
        // Given
        final Path home = Path.of(System.getProperty("user.home"));
        assumeTrue(Files.isWritable(home), "the user's home is not writable on this host");
        final Sandbox sandbox = provider.create(spec());

        // When
        final ShellResult outside = sandbox.run("touch '" + home.resolve(".dmipi-coder-test-probe") + "'", Duration.ofSeconds(10), new CancelToken());

        // Then
        assertThat(outside.succeeded()).isFalse();
        assertThat(home.resolve(".dmipi-coder-test-probe")).doesNotExist();
    }

    @Test
    @DisplayName("when the project is the user's home, the probe falls back instead of failing creation")
    void should_fall_back_when_home_is_an_allowed_path() {
        // Given
        final Path home = Path.of(System.getProperty("user.home"));
        assumeTrue(Files.isWritable(home), "the user's home is not writable on this host");

        // When: home is the project directory, so a home write legitimately succeeds in the sandbox
        final Sandbox sandbox = provider.create(new SandboxSpec(home, List.of(), Duration.ofSeconds(5), Duration.ofSeconds(120)));

        // Then: creation passed the probe anyway — the probe targeted the fallback, not home
        assertThat(sandbox.confines()).isTrue();
    }

    @Test
    @DisplayName("the network contract translates: isolated unshares the net, proxied blackholes DNS and sets the proxy env")
    void should_translate_the_network_contract_into_argv() {
        // Given
        final BubblewrapSandbox isolated = new BubblewrapSandbox(spec().withNetwork(new SandboxNetwork.Isolated()), ResourceLimits.none());
        final BubblewrapSandbox proxied = new BubblewrapSandbox(spec().withNetwork(new SandboxNetwork.Proxied(8081, "tok")), ResourceLimits.none());
        final BubblewrapSandbox open = new BubblewrapSandbox(spec(), ResourceLimits.none());

        // When / Then
        assertThat(isolated.wrapped("echo hi")).contains("--unshare-net");
        assertThat(proxied.wrapped("echo hi")).containsSequence("--ro-bind", "/dev/null", "/etc/resolv.conf");
        assertThat(proxied.wrapped("echo hi")).containsSequence("--setenv", "HTTPS_PROXY", "http://coder:tok@127.0.0.1:8081");
        assertThat(proxied.wrapped("echo hi")).doesNotContain("--unshare-net");
        assertThat(open.wrapped("echo hi")).doesNotContain("--unshare-net", "--setenv");
    }

    @Test
    @DisplayName("an isolated sandbox sees only the loopback interface")
    void should_isolate_the_network() {
        // Given
        final BubblewrapSandbox sandbox = new BubblewrapSandbox(spec().withNetwork(new SandboxNetwork.Isolated()), ResourceLimits.none());

        // When: /proc/net reflects the reader's network namespace
        final ShellResult interfaces = sandbox.run("cat /proc/net/dev", Duration.ofSeconds(10), new CancelToken());

        // Then: loopback is the only interface
        assertThat(interfaces.succeeded()).isTrue();
        assertThat(interfaces.stdout().lines().filter(line -> line.contains(":")).toList())
                .singleElement()
                .satisfies(line -> assertThat(line).contains("lo:"));
    }

    @Test
    @DisplayName("a proxied sandbox has its DNS blackholed and the proxy env set")
    void should_blackhole_dns_and_set_the_proxy_environment() {
        // Given
        final BubblewrapSandbox sandbox = new BubblewrapSandbox(spec().withNetwork(new SandboxNetwork.Proxied(8081, "tok")), ResourceLimits.none());

        // When: resolv.conf is masked by /dev/null — empty on normal hosts, unopenable in nested
        // namespaces without device permissions; either way the host's nameservers must be gone
        final ShellResult result = sandbox.run("cat /etc/resolv.conf 2>/dev/null; printenv HTTPS_PROXY", Duration.ofSeconds(10), new CancelToken());

        // Then: no nameserver leaked through, and the proxy env is set
        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout().strip()).isEqualTo("http://coder:tok@127.0.0.1:8081");
    }

    @Test
    @DisplayName("end to end: a proxied command reaches an allowed origin through the policy, and only through it")
    void should_route_egress_through_the_proxy() throws IOException {
        // Given: a host-side origin and a proxy allowing only it
        assumeTrue(commandExists("curl"), "curl is not installed on this host");
        final HttpServer origin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        origin.createContext("/", httpExchange -> {
            final byte[] body = "hello from origin".getBytes(StandardCharsets.US_ASCII);
            httpExchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = httpExchange.getResponseBody()) {
                out.write(body);
            }
        });
        origin.start();
        try (EgressProxy proxy = new EgressProxy(host -> host.equals("127.0.0.1"), "tok")) {
            final SandboxNetwork.Proxied network = new SandboxNetwork.Proxied(proxy.port(), "tok");
            final BubblewrapSandbox sandbox = new BubblewrapSandbox(spec().withNetwork(network), ResourceLimits.none());

            // When: curl honors the proxy env the sandbox sets
            final String originUrl = "http://127.0.0.1:" + origin.getAddress().getPort() + "/";
            final ShellResult allowed = sandbox.run("curl -s " + originUrl, Duration.ofSeconds(15), new CancelToken());
            final ShellResult blocked = sandbox.run("curl -s http://blocked.example/", Duration.ofSeconds(15), new CancelToken());

            // Then
            assertThat(allowed.succeeded()).isTrue();
            assertThat(allowed.stdout()).isEqualTo("hello from origin");
            assertThat(blocked.stdout()).contains("Blocked by the egress policy: blocked.example");
        } finally {
            origin.stop(0);
        }
    }

    private static boolean commandExists(final String command) {
        return ProcessRunner.run(List.of("sh", "-c", "command -v " + command), Path.of("."), Duration.ofSeconds(5), new CancelToken()).succeeded();
    }

    private static boolean systemdUserScopeWorks() {
        final List<String> scopedTrue = List.of("systemd-run", "--user", "--scope", "--quiet", "true");
        return ProcessRunner.run(scopedTrue, Path.of("."), Duration.ofSeconds(10), new CancelToken()).succeeded();
    }

    private SandboxSpec spec() {
        return new SandboxSpec(projectDirectory, List.of(additionalDirectory), Duration.ofSeconds(5), Duration.ofSeconds(120));
    }
}
