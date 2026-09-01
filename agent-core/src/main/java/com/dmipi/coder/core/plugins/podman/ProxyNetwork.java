package com.dmipi.coder.core.plugins.podman;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * The rootless netmode that exposes the host loopback to the container, so the core's egress
 * proxy is reachable. Both modes present the host loopback at the same per-session
 * {@code 10.x.y.2} address ({@link #randomHostLoopback}): pasta is told to map it directly;
 * slirp4netns fixes the host at the {@code .2} of its subnet, so the subnet is derived from the
 * address. Selected by which helper binary the host actually has.
 */
enum ProxyNetwork {

    /** The modern default helper; the host loopback is mapped explicitly. */
    PASTA("pasta") {
        @Override
        String flag(final String hostLoopback) {
            return "--network=pasta:--map-host-loopback," + hostLoopback;
        }
    },

    /** The legacy helper; {@code allow_host_loopback} exposes the host loopback at the subnet's {@code .2}. */
    SLIRP4NETNS("slirp4netns") {
        @Override
        String flag(final String hostLoopback) {
            final String subnet = hostLoopback.substring(0, hostLoopback.lastIndexOf('.'));
            return "--network=slirp4netns:allow_host_loopback=true,cidr=" + subnet + ".0/24";
        }
    };

    private final String executable;

    ProxyNetwork(final String executable) {
        this.executable = executable;
    }

    /** The {@code --network=...} argument routing this netmode's traffic so {@code hostLoopback} reaches the host. */
    abstract String flag(String hostLoopback);

    /**
     * A per-session address the container reaches the host's loopback at: {@code 10.x.y.2},
     * the last octet fixed by slirp4netns's host mapping. Random so no fixed LAN address is
     * shadowed in every session — a collision-avoidance measure, not a secret: the address is
     * visible in the container's proxy environment, and the proxy token is the actual guard.
     */
    static String randomHostLoopback(final RandomGenerator random) {
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + ".2";
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
