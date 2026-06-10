package com.dmipi.coder.core.infrastructure.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Classifies a hostname as pointing at a loopback, link-local, or private address, so a redirect
 * cannot steer an approved fetch at internal services or the cloud metadata endpoint
 * ({@code 169.254.169.254}) — the classic SSRF pivot. Fails closed: a host that does not
 * resolve, or does not resolve in time, is treated as private.
 */
final class PrivateAddresses {

    private static final int RESOLVE_TIMEOUT_SECONDS = 5;

    private PrivateAddresses() {
    }

    static boolean isPrivate(final String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        final Optional<InetAddress[]> addresses = resolveWithinTimeout(host);
        if (addresses.isEmpty()) {
            return true;
        }
        for (final InetAddress address : addresses.orElseThrow()) {
            if (isBlocked(address)) {
                return true;
            }
        }
        return false;
    }

    /** Resolves on a virtual thread so a hung DNS lookup cannot stall the fetch past the timeout. */
    private static Optional<InetAddress[]> resolveWithinTimeout(final String host) {
        final CompletableFuture<Optional<InetAddress[]>> lookup =
                CompletableFuture.supplyAsync(() -> resolve(host), task -> Thread.ofVirtual().name("dns-resolve").start(task));
        try {
            return lookup.get(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (final ExecutionException | TimeoutException failure) {
            return Optional.empty();
        }
    }

    private static Optional<InetAddress[]> resolve(final String host) {
        try {
            return Optional.of(InetAddress.getAllByName(host));
        } catch (final UnknownHostException unresolved) {
            return Optional.empty();
        }
    }

    private static boolean isBlocked(final InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address);
    }

    private static boolean isUniqueLocalIpv6(final InetAddress address) {
        final byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
