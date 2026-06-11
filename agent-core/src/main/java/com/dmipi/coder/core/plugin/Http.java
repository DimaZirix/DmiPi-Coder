package com.dmipi.coder.core.plugin;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * The http capability: bounded http(s) exchanges guarded by the core. {@link #fetch} serves
 * model-chosen URLs — every redirect hop is screened and private/link-local/unresolvable hosts
 * are refused (the SSRF pivot). {@link #post} serves operator-configured endpoints (an MCP
 * server from {@code .mcp.json} is typically local): http(s)-only, bounded, but not
 * private-host-screened — the URL came from the user's own configuration, not the model.
 * A refused or failed exchange raises {@link java.io.UncheckedIOException}.
 */
public interface Http {

    Response fetch(String url);

    /** POSTs the body with the given headers; redirects are not followed; non-2xx comes back as a status, not an exception. */
    Exchange post(String url, String body, Map<String, String> headers, Duration timeout);

    /** A successful (2xx) fetch: the content type header (may be empty) and the capped, decoded body. */
    record Response(String contentType, String body) {

        public Response {
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(body, "body");
        }

        public boolean isHtml() {
            // No Content-Type means "unknown", not HTML: stripping angle brackets from raw code destroys it.
            return contentType.contains("html");
        }
    }

    /** One POST exchange: status, content type, capped decoded body, and the response headers (first values, lower-cased names). */
    record Exchange(int status, String contentType, String body, Map<String, String> headers) {

        public Exchange {
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(body, "body");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        }

        public boolean ok() {
            return status / 100 == 2;
        }
    }
}
