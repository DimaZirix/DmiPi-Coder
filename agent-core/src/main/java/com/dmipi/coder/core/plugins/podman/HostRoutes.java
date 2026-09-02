package com.dmipi.coder.core.plugins.podman;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The host's IPv4 route table, read from {@code /proc/net/route} — every subnet the host can
 * actually reach (connected LANs, bridges, VPN routes) appears there, so a proxy subnet chosen
 * to overlap none of them shadows nothing the workload might need.
 */
final class HostRoutes {

    private static final Path ROUTE_TABLE = Path.of("/proc/net/route");
    private static final int MASK_COLUMN = 7;

    private HostRoutes() {
    }

    /** One route: destination network and mask, both in host byte order. */
    record Route(int destination, int mask) {
    }

    static List<Route> read() {
        try {
            return parse(Files.readAllLines(ROUTE_TABLE));
        } catch (final IOException unreadable) {
            // Best effort: without a route table there is nothing to avoid — the random draw stands on its own.
            return List.of();
        }
    }

    /** The file is a header line, then whitespace-separated columns with little-endian hex addresses. */
    static List<Route> parse(final List<String> lines) {
        return lines.stream()
                .skip(1)
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.split("\\s+"))
                .filter(columns -> columns.length > MASK_COLUMN)
                .map(columns -> new Route(littleEndianAddress(columns[1]), littleEndianAddress(columns[MASK_COLUMN])))
                .toList();
    }

    private static int littleEndianAddress(final String hex) {
        return Integer.reverseBytes(Integer.parseUnsignedInt(hex, 16));
    }
}
