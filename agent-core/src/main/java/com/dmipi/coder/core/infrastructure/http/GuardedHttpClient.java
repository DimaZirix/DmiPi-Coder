package com.dmipi.coder.core.infrastructure.http;

import com.dmipi.coder.core.plugin.Http;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The core's http capability. Every hop — the initial URL and each redirect — is screened:
 * http(s) only, no private/link-local/unresolvable hosts. Redirects are followed manually and
 * bounded; the body is read up to a byte cap and decoded by the response charset.
 *
 * <p>Honest limit: the screen resolves DNS separately from the connection, which resolves
 * again — a DNS-rebinding attacker keeps that TOCTOU window; {@code java.net.http} offers no
 * seam to pin the screened address to the socket.
 */
public final class GuardedHttpClient implements Http {

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final Predicate<String> privateHost;

    public GuardedHttpClient() {
        this(PrivateAddresses::isPrivate);
    }

    /** Test seam: loopback stubs would otherwise be refused by the private-address screen. */
    public GuardedHttpClient(final Predicate<String> privateHost) {
        this.privateHost = privateHost;
    }

    @Override
    public Response fetch(final String url) {
        try {
            return follow(URI.create(url));
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not fetch " + url + ": " + failure.getMessage(), failure);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException("Interrupted while fetching " + url + ".", new IOException(interrupted));
        } catch (final IllegalArgumentException invalid) {
            throw new UncheckedIOException("Not a fetchable URL: " + url, new IOException(invalid));
        }
    }

    private Response follow(final URI initial) throws IOException, InterruptedException {
        URI current = screened(initial);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            final HttpResponse<InputStream> response = send(current);
            final Optional<URI> redirect = redirectTarget(current, response);
            if (redirect.isPresent()) {
                discard(response);
                current = screened(redirect.orElseThrow());
                continue;
            }
            if (response.statusCode() / 100 != 2) {
                discard(response);
                throw new IOException("HTTP " + response.statusCode());
            }
            return new Response(contentType(response), body(response));
        }
        throw new IOException("more than " + MAX_REDIRECTS + " redirects");
    }

    private URI screened(final URI target) throws IOException {
        final String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("refusing a non-http(s) URL (" + target + ")");
        }
        if (privateHost.test(target.getHost())) {
            throw new IOException("refusing a private, link-local, or unresolvable host (" + target.getHost() + ")");
        }
        return target;
    }

    private HttpResponse<InputStream> send(final URI uri) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(FETCH_TIMEOUT)
                .header("Accept", "text/html,text/plain,*/*")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static Optional<URI> redirectTarget(final URI current, final HttpResponse<InputStream> response) {
        if (response.statusCode() / 100 != 3) {
            return Optional.empty();
        }
        return response.headers().firstValue("location").map(current::resolve);
    }

    private static String contentType(final HttpResponse<?> response) {
        return response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
    }

    private static String body(final HttpResponse<InputStream> response) throws IOException {
        try (InputStream stream = response.body()) {
            final byte[] bytes = stream.readNBytes(MAX_BODY_BYTES);
            return new String(bytes, charset(contentType(response)));
        }
    }

    private static Charset charset(final String contentType) {
        final int marker = contentType.indexOf("charset=");
        if (marker < 0) {
            return StandardCharsets.UTF_8;
        }
        final String name = contentType.substring(marker + "charset=".length()).split("[;\\s]")[0].trim();
        try {
            return Charset.forName(name);
        } catch (final IllegalArgumentException unknown) {
            return StandardCharsets.UTF_8;
        }
    }

    private static void discard(final HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (final IOException ignored) {
            // Best effort: this response is being discarded before a redirect hop.
        }
    }
}
