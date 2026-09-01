package com.dmipi.coder.core.plugins.podman;

import java.util.Objects;

/** The resolved way to the host-side egress proxy: which netmode, and at which mapped address the container reaches the host loopback. */
record ProxyRoute(ProxyNetwork network, String hostLoopback) {

    ProxyRoute {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(hostLoopback, "hostLoopback");
    }

    String flag() {
        return network.flag(hostLoopback);
    }
}
