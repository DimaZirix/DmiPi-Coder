package com.dmipi.coder.core.infrastructure.shell.egress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A minimal loopback HTTP proxy enforcing a hostname policy on sandboxed commands. HTTPS goes
 * through CONNECT tunnels (no TLS interception — only the hostname is seen and judged); plain
 * HTTP is forwarded with the request line rewritten to origin form. The sandbox reaches it via
 * HTTP(S)_PROXY while its own DNS is blackholed, so direct-by-hostname egress fails and the
 * proxy becomes the only resolver. It decides nothing itself: the policy predicate does.
 */
public final class EgressProxy implements AutoCloseable {

    private static final String CONNECT_OK = "HTTP/1.1 200 Connection Established\r\n\r\n";
    private static final String FORBIDDEN = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nBlocked by the egress policy: ";
    private static final String AUTH_REQUIRED = "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"dmipi-coder\"\r\nConnection: close\r\n\r\n";
    private static final String PROXY_USER = "coder";
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int DEFAULT_TLS_PORT = 443;
    private static final int ACCEPT_BACKLOG = 64;

    private final Predicate<String> hostAllowed;
    private final String expectedAuthorization;
    private final ServerSocket server;

    /** With a token, only clients presenting {@code coder:<token>} proxy credentials are served — another instance's sandbox cannot borrow this proxy's policy. An empty token disables the check. */
    public EgressProxy(final Predicate<String> hostAllowed, final String token) {
        this.hostAllowed = hostAllowed;
        this.expectedAuthorization = token.isEmpty() ? "" : "basic " + Base64.getEncoder().encodeToString((PROXY_USER + ":" + token).getBytes(StandardCharsets.US_ASCII)).toLowerCase(Locale.ROOT);
        try {
            this.server = new ServerSocket(0, ACCEPT_BACKLOG, InetAddress.getLoopbackAddress());
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not start the egress proxy on loopback", e);
        }
        Thread.ofVirtual().name("egress-proxy-accept").start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (final IOException ignored) {
            // shutting down; every per-connection thread ends when its sockets die
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                final Socket client = server.accept();
                Thread.ofVirtual().name("egress-proxy-conn").start(() -> handleQuietly(client));
            } catch (final IOException closed) {
                return;
            }
        }
    }

    private void handleQuietly(final Socket client) {
        try (client) {
            handle(client);
        } catch (final IOException ignored) {
            // a torn connection ends the exchange; nothing to report
        }
    }

    private void handle(final Socket client) throws IOException {
        final InputStream in = client.getInputStream();
        final String head = readHead(in);
        if (head.isEmpty()) {
            return;
        }
        final String[] requestLine = head.lines().findFirst().orElse("").split(" ");
        if (requestLine.length < 2) {
            return;
        }
        if (!authorized(head)) {
            client.getOutputStream().write(AUTH_REQUIRED.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            return;
        }
        if (requestLine[0].equals("CONNECT")) {
            tunnel(client, requestLine[1]);
            return;
        }
        forwardPlainHttp(client, head, requestLine);
    }

    /** HTTPS path: judge the CONNECT hostname, then pump bytes blindly — content stays end-to-end encrypted. */
    private void tunnel(final Socket client, final String target) throws IOException {
        final String host = target.contains(":") ? target.substring(0, target.lastIndexOf(':')) : target;
        final int port = target.contains(":") ? Integer.parseInt(target.substring(target.lastIndexOf(':') + 1)) : DEFAULT_TLS_PORT;
        if (!hostAllowed.test(host)) {
            refuse(client, host);
            return;
        }
        try (Socket origin = new Socket(host, port)) {
            client.getOutputStream().write(CONNECT_OK.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            pumpBothWays(client, origin);
        }
    }

    private void forwardPlainHttp(final Socket client, final String head, final String[] requestLine) throws IOException {
        final HttpTarget target = HttpTarget.parse(requestLine[1]);
        if (target == null) {
            refuse(client, requestLine[1]);
            return;
        }
        if (!hostAllowed.test(target.host())) {
            refuse(client, target.host());
            return;
        }
        try (Socket origin = new Socket(target.host(), target.port())) {
            final OutputStream toOrigin = origin.getOutputStream();
            // Origin servers expect origin-form ("GET /path"), not the proxy-form absolute URI.
            toOrigin.write((requestLine[0] + " " + target.pathAndQuery() + " HTTP/1.1\r\n").getBytes(StandardCharsets.US_ASCII));
            toOrigin.write(head.substring(head.indexOf("\r\n") + 2).getBytes(StandardCharsets.US_ASCII));
            toOrigin.flush();
            pumpBothWays(client, origin);
        }
    }

    private boolean authorized(final String head) {
        if (expectedAuthorization.isEmpty()) {
            return true;
        }
        return head.lines()
                .map(line -> line.toLowerCase(Locale.ROOT))
                .filter(line -> line.startsWith("proxy-authorization:"))
                .map(line -> line.substring("proxy-authorization:".length()).strip())
                .anyMatch(expectedAuthorization::equals);
    }

    private void refuse(final Socket client, final String host) throws IOException {
        client.getOutputStream().write((FORBIDDEN + host + "\n").getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
    }

    private void pumpBothWays(final Socket client, final Socket origin) throws IOException {
        final Thread upstream = Thread.ofVirtual().start(() -> pumpQuietly(client, origin));
        origin.getInputStream().transferTo(client.getOutputStream());
        try {
            upstream.join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pumpQuietly(final Socket client, final Socket origin) {
        try {
            client.getInputStream().transferTo(origin.getOutputStream());
            origin.shutdownOutput();
        } catch (final IOException ignored) {
            // one direction closing ends the tunnel; the other direction observes it
        }
    }

    /** Reads exactly up to the blank line, byte-wise, so no body bytes are swallowed. */
    private static String readHead(final InputStream in) throws IOException {
        final StringBuilder head = new StringBuilder();
        int previous = -1;
        for (int b = in.read(); b >= 0; b = in.read()) {
            head.append((char) b);
            if (b == '\n' && previous == '\n') {
                break;
            }
            if (b != '\r') {
                previous = b;
            }
        }
        return head.toString();
    }

    private record HttpTarget(String host, int port, String pathAndQuery) {

        private static HttpTarget parse(final String absoluteUri) {
            if (!absoluteUri.startsWith("http://")) {
                return null;
            }
            final String rest = absoluteUri.substring("http://".length());
            final int slash = rest.indexOf('/');
            final String authority = slash < 0 ? rest : rest.substring(0, slash);
            final String path = slash < 0 ? "/" : rest.substring(slash);
            final int colon = authority.lastIndexOf(':');
            final String host = colon < 0 ? authority : authority.substring(0, colon);
            final int port = colon < 0 ? DEFAULT_HTTP_PORT : Integer.parseInt(authority.substring(colon + 1));
            return new HttpTarget(host, port, path);
        }
    }
}
