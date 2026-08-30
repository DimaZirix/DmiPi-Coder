package com.dmipi.coder.core.plugins.podman;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The rootless netmode that exposes the host loopback to the container, so the core's egress
 * proxy is reachable. Both modes present the host loopback as {@link #HOST_LOOPBACK}: slirp4netns
 * exposes it there natively; pasta is told to map the same address, so the proxy URL is
 * netmode-independent. Selected by which helper binary the host actually has.
 */
enum ProxyNetwork {

    /** The modern default helper; the host loopback is mapped explicitly. */
    PASTA("pasta", "--network=pasta:--map-host-loopback," + ProxyNetwork.HOST_LOOPBACK),

    /** The legacy helper; {@code allow_host_loopback} exposes the host loopback at 10.0.2.2. */
    SLIRP4NETNS("slirp4netns", "--network=slirp4netns:allow_host_loopback=true");

    /** Where the container reaches the host's loopback under either helper. */
    static final String HOST_LOOPBACK = "10.0.2.2";

    private final String executable;
    private final String flag;

    ProxyNetwork(final String executable, final String flag) {
        this.executable = executable;
        this.flag = flag;
    }

    String flag() {
        return flag;
    }

    /** Prefers pasta (podman's own default), falls back to slirp4netns; empty when neither helper is installed. */
    static Optional<ProxyNetwork> autoSelect(final Predicate<String> executableAvailable) {
        if (executableAvailable.test(PASTA.executable)) {
            return Optional.of(PASTA);
        }
        if (executableAvailable.test(SLIRP4NETNS.executable)) {
            return Optional.of(SLIRP4NETNS);
        }
        return Optional.empty();
    }
}
