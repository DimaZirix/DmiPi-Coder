package com.dmipi.coder.core.infrastructure.shell.egress;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EgressProxyTest {

    private static final int READ_TIMEOUT_MS = 5_000;

    @Test
    @DisplayName("a plain HTTP request to an allowed host is forwarded in origin form")
    void should_forward_plain_http_to_an_allowed_host() throws IOException {
        try (AutoCloseHttpServer origin = AutoCloseHttpServer.replyingHello()) {
            try (EgressProxy proxy = new EgressProxy(host -> true, "")) {
                // When
                final String response = exchange(proxy, "GET http://127.0.0.1:" + origin.port() + "/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");

                // Then
                assertThat(response).contains("hello from origin");
            }
        }
    }

    @Test
    @DisplayName("a request to a blocked host is refused with 403, never forwarded")
    void should_refuse_a_blocked_host() throws IOException {
        try (EgressProxy proxy = new EgressProxy(host -> false, "")) {
            // When
            final String response = exchange(proxy, "GET http://blocked.example/ HTTP/1.1\r\nHost: blocked.example\r\n\r\n");

            // Then
            assertThat(response).contains("403");
            assertThat(response).contains("Blocked by the egress policy: blocked.example");
        }
    }

    @Test
    @DisplayName("CONNECT judges the hostname, then tunnels bytes blindly both ways")
    void should_tunnel_a_connect_exchange() throws IOException {
        try (ServerSocket echo = echoServer()) {
            try (EgressProxy proxy = new EgressProxy(host -> true, "")) {
                try (Socket client = connect(proxy)) {
                    client.getOutputStream().write(("CONNECT 127.0.0.1:" + echo.getLocalPort() + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();

                    // Then: the tunnel is established and bytes echo through it
                    assertThat(readHead(client.getInputStream())).contains("200 Connection Established");
                    client.getOutputStream().write("ping\n".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    assertThat(new String(client.getInputStream().readNBytes(5), StandardCharsets.US_ASCII)).isEqualTo("ping\n");
                }
            }
        }
    }

    @Test
    @DisplayName("with a token, a client without the right proxy credentials gets 407; with them, service")
    void should_require_the_proxy_token() throws IOException {
        try (AutoCloseHttpServer origin = AutoCloseHttpServer.replyingHello()) {
            try (EgressProxy proxy = new EgressProxy(host -> true, "secret")) {
                // When
                final String unauthorized = exchange(proxy, "GET http://127.0.0.1:" + origin.port() + "/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
                final String credentials = Base64.getEncoder().encodeToString("coder:secret".getBytes(StandardCharsets.US_ASCII));
                final String authorized = exchange(proxy, "GET http://127.0.0.1:" + origin.port() + "/ HTTP/1.1\r\nHost: 127.0.0.1\r\nProxy-Authorization: Basic " + credentials + "\r\nConnection: close\r\n\r\n");

                // Then
                assertThat(unauthorized).contains("407");
                assertThat(authorized).contains("hello from origin");
            }
        }
    }

    private static String exchange(final EgressProxy proxy, final String request) throws IOException {
        try (Socket client = connect(proxy)) {
            client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            // EOF towards the proxy, as a real client closing its request side would — the pump ends on it.
            client.shutdownOutput();
            return new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static Socket connect(final EgressProxy proxy) throws IOException {
        final Socket client = new Socket(InetAddress.getLoopbackAddress(), proxy.port());
        client.setSoTimeout(READ_TIMEOUT_MS);
        return client;
    }

    private static String readHead(final InputStream in) throws IOException {
        final StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            final int b = in.read();
            if (b < 0) {
                break;
            }
            head.append((char) b);
        }
        return head.toString();
    }

    private static ServerSocket echoServer() throws IOException {
        final ServerSocket echo = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket connection = echo.accept()) {
                connection.getInputStream().transferTo(connection.getOutputStream());
            } catch (final IOException ignored) {
                // the test closing its sockets ends the echo
            }
        });
        return echo;
    }

    /** A one-page origin server that closes with the test. */
    private record AutoCloseHttpServer(HttpServer server) implements AutoCloseable {

        private static AutoCloseHttpServer replyingHello() throws IOException {
            final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", httpExchange -> {
                final byte[] body = "hello from origin".getBytes(StandardCharsets.US_ASCII);
                httpExchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = httpExchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return new AutoCloseHttpServer(server);
        }

        private int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
