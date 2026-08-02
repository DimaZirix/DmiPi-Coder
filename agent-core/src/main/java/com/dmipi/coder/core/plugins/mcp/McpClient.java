package com.dmipi.coder.core.plugins.mcp;

import com.dmipi.coder.core.plugin.Http;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Minimal MCP client for the streamable-http transport: JSON-RPC 2.0 requests POSTed through
 * the http capability, answered either as plain JSON or as an SSE stream whose data events
 * carry the JSON-RPC messages. Thread-safe: request ids are atomic, the session id is a
 * volatile single value.
 */
final class McpClient {

    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final String SESSION_HEADER = "mcp-session-id";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Http http;
    private final McpServerConfig server;
    private final AtomicLong requestIds = new AtomicLong();
    private volatile String sessionId;

    McpClient(final Http http, final McpServerConfig server) {
        this.http = http;
        this.server = server;
    }

    /** Runs the initialize handshake and returns every tool the server advertises, following pagination. */
    List<McpRemoteTool> connect() {
        final ObjectNode initializeParams = MAPPER.createObjectNode();
        initializeParams.put("protocolVersion", PROTOCOL_VERSION);
        initializeParams.set("capabilities", MAPPER.createObjectNode());
        final ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "dmipi-coder");
        clientInfo.put("version", "0.1");
        initializeParams.set("clientInfo", clientInfo);
        request("initialize", initializeParams);

        notification("notifications/initialized");
        return listTools();
    }

    /** Calls the named remote tool with the given JSON argument object; returns its concatenated text content. */
    CallResult callTool(final String toolName, final String argumentsJson) {
        final ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", MAPPER.readTree(argumentsJson.isBlank() ? "{}" : argumentsJson));
        final JsonNode result = request("tools/call", params);

        final StringBuilder text = new StringBuilder();
        for (final JsonNode content : result.path("content")) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(contentText(content));
        }
        return new CallResult(text.toString(), result.path("isError").asBoolean(false));
    }

    record CallResult(String text, boolean error) {
    }

    private List<McpRemoteTool> listTools() {
        final List<McpRemoteTool> tools = new ArrayList<>();
        String cursor = null;
        do {
            final ObjectNode params = MAPPER.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            final JsonNode result = request("tools/list", params);
            for (final JsonNode tool : result.path("tools")) {
                tools.add(remoteTool(tool));
            }
            cursor = result.path("nextCursor").isString() ? result.path("nextCursor").stringValue() : null;
        } while (cursor != null);
        return List.copyOf(tools);
    }

    private McpRemoteTool remoteTool(final JsonNode tool) {
        final String name = tool.path("name").isString() ? tool.path("name").stringValue() : "";
        if (name.isBlank()) {
            throw new IllegalStateException("MCP server '" + server.name() + "' advertised a tool without a name: " + tool);
        }
        final String description = tool.path("description").isString() ? tool.path("description").stringValue() : "";
        final JsonNode schema = tool.path("inputSchema");
        final boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean(false);
        return new McpRemoteTool(name, description, schema.isObject() ? schema.toString() : "{\"type\": \"object\"}", readOnly);
    }

    private static String contentText(final JsonNode content) {
        final String type = content.path("type").isString() ? content.path("type").stringValue() : "";
        if (type.equals("text")) {
            return content.path("text").isString() ? content.path("text").stringValue() : "";
        }
        return "[non-text content of type '" + type + "' omitted]";
    }

    private JsonNode request(final String method, final ObjectNode params) {
        final long id = requestIds.incrementAndGet();
        final ObjectNode message = envelope(method, params);
        message.put("id", id);

        final Http.Exchange exchange = post(message);
        final JsonNode reply = replyWithId(exchange, id);
        final JsonNode error = reply.path("error");
        if (error.isObject()) {
            throw new IllegalStateException("MCP server '" + server.name() + "' returned error " + error.path("code") + " for " + method + ": " + error.path("message").stringValue());
        }
        return reply.path("result");
    }

    private void notification(final String method) {
        post(envelope(method, null));
    }

    private ObjectNode envelope(final String method, final ObjectNode params) {
        final ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        return message;
    }

    private Http.Exchange post(final ObjectNode message) {
        final Map<String, String> headers = new HashMap<>(Map.of(
                "Content-Type", "application/json",
                "Accept", "application/json, text/event-stream",
                "MCP-Protocol-Version", PROTOCOL_VERSION));
        final String session = sessionId;
        if (session != null) {
            headers.put("Mcp-Session-Id", session);
        }

        final Http.Exchange exchange = http.post(server.url(), MAPPER.writeValueAsString(message), headers, server.requestTimeout());
        if (!exchange.ok()) {
            throw new UncheckedIOException(new java.io.IOException("MCP server '" + server.name() + "' answered HTTP " + exchange.status() + " at " + server.url()));
        }
        final String announced = exchange.headers().get(SESSION_HEADER);
        if (announced != null) {
            sessionId = announced;
        }
        return exchange;
    }

    /**
     * The JSON-RPC reply carrying the given id — direct JSON, or dug out of the SSE events.
     * SSE framing per spec: an event's payload is every consecutive {@code data:} line joined
     * with newlines, terminated by a blank line — a large reply may span several data lines.
     */
    private JsonNode replyWithId(final Http.Exchange exchange, final long id) {
        if (!exchange.contentType().contains("text/event-stream")) {
            return MAPPER.readTree(exchange.body());
        }
        final StringBuilder data = new StringBuilder();
        for (final String raw : exchange.body().split("\n", -1)) {
            final String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length() + (line.startsWith("data: ") ? 1 : 0)));
                continue;
            }
            if (line.isEmpty() && !data.isEmpty()) {
                final JsonNode reply = eventWithId(data.toString(), id);
                data.setLength(0);
                if (reply != null) {
                    return reply;
                }
            }
        }
        if (!data.isEmpty()) {
            final JsonNode reply = eventWithId(data.toString(), id);
            if (reply != null) {
                return reply;
            }
        }
        throw new IllegalStateException("MCP server '" + server.name() + "' closed the SSE stream without answering request " + id + ".");
    }

    /** The event parsed as the awaited reply, or null when it is another message (or not JSON at all). */
    private static JsonNode eventWithId(final String payload, final long id) {
        final JsonNode message;
        try {
            message = MAPPER.readTree(payload);
        } catch (final RuntimeException notJson) {
            // A foreign, non-JSON event on the stream is not ours to reject; the awaited reply is still searched for.
            return null;
        }
        return matchesId(message.path("id"), id) ? message : null;
    }

    /** JSON-RPC requires the id echoed back with the same value; a server answering with its string form is accepted too. */
    private static boolean matchesId(final JsonNode id, final long expected) {
        if (id.isIntegralNumber()) {
            return id.longValue() == expected;
        }
        return id.isString() && id.stringValue().equals(Long.toString(expected));
    }
}
