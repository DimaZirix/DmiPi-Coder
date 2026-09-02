package com.dmipi.coder.core.plugins.podman;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * The rootless netmode that exposes the host loopback to the container, so the core's egress
 * proxy is reachable. Both modes present the host loopback at the same per-session address —
 * the {@code .2} of a route-checked random RFC1918 subnet ({@link #randomHostLoopback}): pasta
 * is told to map it directly; slirp4netns fixes the host at the {@code .2} of its subnet, so
 * the subnet is derived from the address. Selected by which helper binary the host actually has.
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
     * A per-session address the container reaches the host's loopback at: the {@code .2} of a
     * random RFC1918 /24 (the last octet fixed by slirp4netns's host mapping) that overlaps
     * none of the host's routes — so no subnet the workload might genuinely need is shadowed.
     * Candidates come from {@code 10/8} first, then {@code 172.16/12} and {@code 192.168/16}
     * for hosts whose VPN routes swallow all of {@code 10/8}; when every candidate collides
     * (everything routed), the first stands — best-effort avoidance, and never a secret: the
     * address is visible in the container's proxy environment, the proxy token is the guard.
     */
    static String randomHostLoopback(final RandomGenerator random, final List<HostRoutes.Route> hostRoutes) {
        final List<Integer> candidates = candidateSubnets(random);
        return candidates.stream()
                .filter(subnet -> !overlapsAny(subnet, hostRoutes))
                .findFirst()
                .map(ProxyNetwork::hostAddress)
                .orElseGet(() -> hostAddress(candidates.getFirst()));
    }

    private static List<Integer> candidateSubnets(final RandomGenerator random) {
        final List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            candidates.add(subnet(10, random.nextInt(256), random.nextInt(256)));
        }
        for (int i = 0; i < 8; i++) {
            candidates.add(subnet(172, 16 + random.nextInt(16), random.nextInt(256)));
        }
        for (int i = 0; i < 8; i++) {
            candidates.add(subnet(192, 168, random.nextInt(256)));
        }
        return candidates;
    }

    private static int subnet(final int first, final int second, final int third) {
        return (first << 24) | (second << 16) | (third << 8);
    }

    /** Two networks overlap when their common prefix matches; a zero mask is the default route — a catch-all, not an occupied subnet. */
    private static boolean overlapsAny(final int subnet, final List<HostRoutes.Route> routes) {
        final int subnetMask = 0xFFFFFF00;
        return routes.stream()
                .anyMatch(route -> route.mask() != 0 && ((subnet ^ route.destination()) & route.mask() & subnetMask) == 0);
    }

    private static String hostAddress(final int subnet) {
        return ((subnet >>> 24) & 255) + "." + ((subnet >>> 16) & 255) + "." + ((subnet >>> 8) & 255) + ".2";
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
