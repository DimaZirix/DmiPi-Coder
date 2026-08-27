package com.dmipi.coder.core.domain.shell;

import java.util.Objects;

/**
 * The resolved network side of the containment contract: what a sandboxed command may reach.
 * The core resolves it — the egress proxy's port and token exist only once the core has started
 * the proxy — and a provider only translates it into its technology's flags. A provider that
 * cannot honour the resolved contract must refuse loudly, never pretend.
 */
public sealed interface SandboxNetwork {

    /** The host network, unrestricted — the honest default until egress control is configured. */
    record Open() implements SandboxNetwork {
    }

    /** No network at all. */
    record Isolated() implements SandboxNetwork {
    }

    /**
     * Egress through the core's loopback proxy: proxy-honoring tools reach {@code 127.0.0.1:port}
     * presenting the token as proxy credentials, and the provider blackholes DNS so
     * direct-by-hostname egress fails. Cooperative by design — a hostile binary dialling raw IP
     * addresses slips past; the threat model is accidents, not adversaries.
     */
    record Proxied(int port, String token) implements SandboxNetwork {

        private static final int MAXIMUM_PORT = 65535;

        public Proxied {
            Objects.requireNonNull(token, "token");
            if (port < 1 || port > MAXIMUM_PORT) {
                throw new IllegalArgumentException("The proxy port must be within 1.." + MAXIMUM_PORT + ", got: " + port);
            }
        }
    }
}
