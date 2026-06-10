package com.dmipi.coder.core.plugin;

import java.util.Objects;

/**
 * The http capability: bounded http(s) fetches guarded by the core — redirects are screened hop
 * by hop, private/link-local/unresolvable hosts are refused (the SSRF pivot), the body is size-
 * capped. A refused or failed fetch raises {@link java.io.UncheckedIOException}.
 */
public interface Http {

    Response fetch(String url);

    /** A successful (2xx) response: the content type header (may be empty) and the capped, decoded body. */
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
}
