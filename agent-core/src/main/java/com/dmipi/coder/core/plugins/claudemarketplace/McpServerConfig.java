package com.dmipi.coder.core.plugins.claudemarketplace;

import java.time.Duration;
import java.util.Objects;

/** One MCP server declared in an {@code .mcp.json}: its name, http endpoint, and per-request timeout. */
record McpServerConfig(String name, String url, Duration requestTimeout) {

    McpServerConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
    }
}
